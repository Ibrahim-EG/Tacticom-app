package com.familyintercom.tacticom

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Direct Kotlin port of tacticom_server.py's session/lobby/profile logic,
 * running on NanoHTTPD/NanoWSD instead of aiohttp. The HTML/JS client is
 * unchanged (served verbatim from assets/web/index.html) -- the WebSocket
 * message protocol is identical, so nothing on the browser side needed to
 * change.
 *
 * One real difference from the Python version: NanoHTTPD services each
 * connection on its own thread rather than a single-threaded event loop,
 * so operations that Python got "for free" atomically (check-then-act on
 * `sessions`, on the active ring) are wrapped in `synchronized` blocks
 * here to keep the same guarantees under real concurrency.
 */
class TacticomServer(
    private val context: Context,
    port: Int,
    private val ringController: RingController,
    private val onLog: (tag: String, message: String) -> Unit,
) : NanoWSD(port) {

    companion object {
        private const val TAG = "TacticomServer"
        private const val MAX_SESSION_NAME_LEN = 32
        private const val MAX_PROFILE_NAME_LEN = 24
        const val RING_DURATION_MS = 15_000L
        const val RING_COOLDOWN_MS = 5_000L
        private const val KEYSTORE_ASSET = "tacticom_cert.p12"
        private const val KEYSTORE_PASSWORD = "tacticom123"
    }

    private class SessionRoom(
        val id: String,
        val displayName: String,
        val passwordHash: String?,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val clients = ConcurrentHashMap<TacticomWebSocket, String>() // conn -> member name
    }

    private class RingState(val id: String, val ringer: String, val initiator: TacticomWebSocket?)

    private val sessions = ConcurrentHashMap<String, SessionRoom>()
    private val sessionsLock = Any()
    private val connections = ConcurrentHashMap<TacticomWebSocket, Boolean>()

    private val ringLock = Any()
    private var activeRing: RingState? = null
    private var lastRingEndedAt: Long? = null

    private val indexHtml: String by lazy {
        context.assets.open("web/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    init {
        makeSecure(buildSslServerSocketFactory(), null)
    }

    private fun buildSslServerSocketFactory(): javax.net.ssl.SSLServerSocketFactory {
        val password = KEYSTORE_PASSWORD.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        context.assets.open(KEYSTORE_ASSET).use { input -> keyStore.load(input, password) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())
        return sslContext.serverSocketFactory
    }

    // ------------------------------------------------------------------
    // Plain HTTP: only the '/' page. Everything else goes over the
    // WebSocket once the page has loaded.
    // ------------------------------------------------------------------
    override fun serve(session: IHTTPSession): Response {
        return if (isWebsocketRequested(session)) {
            super.serve(session)
        } else if (session.uri == "/") {
            newFixedLengthResponse(Response.Status.OK, "text/html", indexHtml)
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = TacticomWebSocket(handshake)

    // ------------------------------------------------------------------
    // One instance per open connection -- doubles as the "ConnState" from
    // the Python version, since NanoWSD already gives each connection its
    // own long-lived object.
    // ------------------------------------------------------------------
    private inner class TacticomWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        val ip: String = handshake.remoteIpAddress ?: "unknown"
        var profile: String? = null
        var session: SessionRoom? = null
        var memberName: String? = null

        override fun onOpen() {
            connections[this] = true
            onLog("CONNECT", "device connected  ($ip)")
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            exitSession(this)
            connections.remove(this)
            onLog("CLOSE", "${profile ?: "device"} disconnected  ($ip)")
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            when (message.opCode) {
                NanoWSD.WebSocketFrame.OpCode.Text -> handleText(this, message.textPayload)
                NanoWSD.WebSocketFrame.OpCode.Binary -> handleBinary(this, message.binaryPayload)
                else -> { /* Ping/Pong/Close frames are handled by NanoWSD itself */ }
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            Log.w(TAG, "websocket error from $ip", exception)
        }

        fun sendJson(obj: JSONObject) {
            try {
                send(obj.toString())
            } catch (e: IOException) {
                // Connection's already going away; onClose will clean it up.
            }
        }
    }

    // ------------------------------------------------------------------
    // Small helpers mirroring the Python versions exactly.
    // ------------------------------------------------------------------
    private fun sanitize(text: String?, maxLen: Int, fallback: String): String {
        val t = text?.trim()
        return if (t.isNullOrEmpty()) fallback else t.take(maxLen)
    }

    private fun normalizeSessionId(text: String?): String? {
        val t = text?.trim()
        if (t.isNullOrEmpty()) return null
        return t.take(MAX_SESSION_NAME_LEN).uppercase()
    }

    private fun randomCallsign(): String = "OP-" + (1000 + SecureRandom().nextInt(9000))

    private fun hashPassword(sessionId: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$sessionId:$password".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // Lobby
    // ------------------------------------------------------------------
    private fun lobbySnapshot(): JSONArray {
        val arr = JSONArray()
        sessions.values.sortedBy { it.createdAt }.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.displayName)
                put("count", s.clients.size)
                put("locked", s.passwordHash != null)
            })
        }
        return arr
    }

    private fun sendLobby(conn: TacticomWebSocket) {
        conn.sendJson(JSONObject().put("type", "lobby").put("sessions", lobbySnapshot()))
    }

    private fun pushLobbyToIdle() {
        connections.keys.forEach { c -> if (c.session == null) sendLobby(c) }
    }

    private fun sendError(conn: TacticomWebSocket, reason: String) {
        conn.sendJson(JSONObject().put("type", "session_error").put("reason", reason))
    }

    // ------------------------------------------------------------------
    // Session broadcast
    // ------------------------------------------------------------------
    private fun broadcastToSessionText(s: SessionRoom, sender: TacticomWebSocket, payload: String) {
        val dead = mutableListOf<TacticomWebSocket>()
        s.clients.keys.forEach { c ->
            if (c === sender) return@forEach
            try { c.send(payload) } catch (e: IOException) { dead.add(c) }
        }
        dead.forEach { s.clients.remove(it) }
    }

    private fun broadcastToSessionBinary(s: SessionRoom, sender: TacticomWebSocket, payload: ByteArray) {
        val dead = mutableListOf<TacticomWebSocket>()
        s.clients.keys.forEach { c ->
            if (c === sender) return@forEach
            try { c.send(payload) } catch (e: IOException) { dead.add(c) }
        }
        dead.forEach { s.clients.remove(it) }
    }

    private fun broadcastPresence(s: SessionRoom) {
        val msg = JSONObject().apply {
            put("type", "presence")
            put("session", s.displayName)
            put("count", s.clients.size)
            put("names", JSONArray(s.clients.values))
        }.toString()
        val dead = mutableListOf<TacticomWebSocket>()
        s.clients.keys.forEach { c -> try { c.send(msg) } catch (e: IOException) { dead.add(c) } }
        dead.forEach { s.clients.remove(it) }
    }

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------
    private fun enterSession(conn: TacticomWebSocket, s: SessionRoom) {
        val base = conn.profile ?: randomCallsign()
        var name = base
        val taken = s.clients.values.toSet()
        var i = 2
        while (taken.contains(name)) { name = "$base-$i"; i++ }

        s.clients[conn] = name
        conn.session = s
        conn.memberName = name

        conn.sendJson(JSONObject().apply {
            put("type", "session_joined")
            put("session", JSONObject().apply {
                put("id", s.id)
                put("name", s.displayName)
                put("locked", s.passwordHash != null)
            })
            put("name", name)
            put("count", s.clients.size)
        })
        broadcastPresence(s)
        onLog("JOIN", "$name joined \"${s.displayName}\"  -  ${s.clients.size} online  (${conn.ip})")
        pushLobbyToIdle()
    }

    private fun exitSession(conn: TacticomWebSocket) {
        val s = conn.session ?: return
        s.clients.remove(conn)
        val name = conn.memberName ?: "device"
        val remaining = s.clients.size
        onLog("LEAVE", "$name left \"${s.displayName}\"  -  $remaining remaining  (${conn.ip})")
        conn.session = null
        conn.memberName = null

        if (s.clients.isNotEmpty()) {
            broadcastPresence(s)
        } else {
            synchronized(sessionsLock) {
                if (sessions[s.id] === s) {
                    sessions.remove(s.id)
                    onLog("CLOSED", "session \"${s.displayName}\" is empty and was removed")
                }
            }
        }
        pushLobbyToIdle()
    }

    private fun handleCreateSession(conn: TacticomWebSocket, data: JSONObject) {
        if (conn.session != null) exitSession(conn)

        val sessionId = normalizeSessionId(data.optString("name", null))
        if (sessionId == null) {
            sendError(conn, "Give the session a name.")
            return
        }

        var created: SessionRoom? = null
        synchronized(sessionsLock) {
            if (!sessions.containsKey(sessionId)) {
                val password = data.optString("password", "").trim()
                created = SessionRoom(
                    id = sessionId,
                    displayName = sessionId,
                    passwordHash = if (password.isNotEmpty()) hashPassword(sessionId, password) else null,
                )
                sessions[sessionId] = created!!
            }
        }
        if (created == null) {
            sendError(conn, "A session named \"$sessionId\" already exists — join it from the lobby instead.")
            return
        }

        val creator = conn.profile ?: randomCallsign()
        onLog("CREATE", "$creator started session \"$sessionId\"  (${conn.ip})")
        enterSession(conn, created!!)
    }

    private fun handleJoinSession(conn: TacticomWebSocket, data: JSONObject) {
        if (conn.session != null) exitSession(conn)

        val sessionId = normalizeSessionId(data.optString("id", null))
        val s = if (sessionId != null) sessions[sessionId] else null
        if (s == null) {
            sendError(conn, "That session just ended.")
            sendLobby(conn)
            return
        }

        if (s.passwordHash != null) {
            val password = data.optString("password", "").trim()
            if (hashPassword(sessionId!!, password) != s.passwordHash) {
                onLog("DENIED", "wrong access code for \"${s.displayName}\"  (${conn.ip})")
                sendError(conn, "Incorrect access code.")
                return
            }
        }

        enterSession(conn, s)
    }

    // ------------------------------------------------------------------
    // Message dispatch
    // ------------------------------------------------------------------
    private fun handleText(conn: TacticomWebSocket, raw: String) {
        val data = try { JSONObject(raw) } catch (e: Exception) { return }

        if (data.has("profile")) {
            conn.profile = sanitize(
                data.optString("profile", null),
                MAX_PROFILE_NAME_LEN,
                conn.profile ?: randomCallsign(),
            )
        }

        when (data.optString("type")) {
            "hello", "list_sessions" -> sendLobby(conn)
            "create_session" -> handleCreateSession(conn, data)
            "join_session" -> handleJoinSession(conn, data)
            "leave_session" -> exitSession(conn)
            "tx_start", "tx_stop" -> conn.session?.let { s -> broadcastToSessionText(s, conn, raw) }
            "ring" -> startRing(conn)
            "ring_stop" -> {
                // No browser button triggers this anymore (stopping happens
                // via the Android notification), but it's kept as a useful
                // testing hook, same as the Python version.
                synchronized(ringLock) { activeRing?.let { stopRing(it.id) } }
            }
        }
    }

    fun handleBinary(conn: TacticomWebSocket, payload: ByteArray) {
        conn.session?.let { s -> broadcastToSessionBinary(s, conn, payload) }
    }

    // ------------------------------------------------------------------
    // Ring the Host -- native Android vibration + notification + looping
    // ringtone, instead of shelling out to termux-api. Same anti-spam
    // rules as the Python version: one ring at a time, short cooldown
    // after it ends.
    // ------------------------------------------------------------------
    private fun startRing(conn: TacticomWebSocket) {
        synchronized(ringLock) {
            if (activeRing != null) {
                sendError(conn, "Already ringing the house phone — hang tight.")
                return
            }
            lastRingEndedAt?.let { last ->
                val waitLeftMs = RING_COOLDOWN_MS - (System.currentTimeMillis() - last)
                if (waitLeftMs > 0) {
                    val waitLeftSec = Math.ceil(waitLeftMs / 1000.0).toInt()
                    sendError(conn, "Please wait ${waitLeftSec}s before ringing again.")
                    return
                }
            }

            val id = UUID.randomUUID().toString().take(8)
            val ringer = conn.profile ?: "Someone"
            activeRing = RingState(id, ringer, conn)
            onLog("RING", "$ringer rang the house phone  (${conn.ip})")

            ringController.ring(ringer, RING_DURATION_MS) { reason -> finishRing(id, reason) }
        }
    }

    /** Early-stop path (from the notification button or the internal
     * ring_stop testing hook). Cancels the physical ring; finishRing()
     * still runs via ringController's callback once it actually stops,
     * always reporting "stopped" as the reason -- this just triggers
     * that path, it doesn't choose the wording. */
    fun stopRing(ringId: String) {
        synchronized(ringLock) {
            val ring = activeRing ?: return
            if (ring.id != ringId) return
        }
        ringController.stop()
    }

    /** Whatever ring is currently active, if any -- used by the
     * notification's Stop button, which doesn't know the ring id. */
    fun stopActiveRing() {
        val id = synchronized(ringLock) { activeRing?.id } ?: return
        stopRing(id)
    }

    private fun finishRing(ringId: String, reason: String) {
        synchronized(ringLock) {
            val ring = activeRing ?: return
            if (ring.id != ringId) return
            activeRing = null
            lastRingEndedAt = System.currentTimeMillis()
            onLog("RING", "ring for ${ring.ringer} ${if (reason == "timeout") "timed out" else "was stopped"}")
            val initiator = ring.initiator
            if (initiator != null && connections.containsKey(initiator)) {
                initiator.sendJson(JSONObject().apply {
                    put("type", "ring_stop")
                    put("id", ringId)
                    put("reason", reason)
                })
            }
        }
    }

    /** Called from the service on shutdown so a Ctrl+C-equivalent (the
     * user stopping the service) can't leave a ring stuck on. */
    fun shutdownCleanup() {
        synchronized(ringLock) {
            if (activeRing != null) {
                ringController.stop()
                activeRing = null
            }
        }
    }
}
