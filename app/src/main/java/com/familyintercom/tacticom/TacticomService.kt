package com.familyintercom.tacticom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Keeps TacticomServer alive whether or not the app's own screen is open,
 * by running as a foreground service -- the same mechanism music players
 * and navigation apps use to survive backgrounding and Doze. Also watches
 * for network changes (switching Wi-Fi networks, reconnecting after a
 * drop) and restarts the server so the address it's actually reachable on
 * never goes stale.
 */
class TacticomService : Service() {

    companion object {
        private const val TAG = "TacticomService"
        const val PORT = 8443
        private const val SERVICE_CHANNEL_ID = "tacticom_service"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val NETWORK_RESTART_DEBOUNCE_MS = 1000L

        @Volatile var isRunning: Boolean = false
            private set
    }

    interface StatusListener {
        fun onStatusChanged(running: Boolean, address: String?)
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): TacticomService = this@TacticomService
    }

    private var statusListener: StatusListener? = null
    fun setStatusListener(listener: StatusListener?) {
        statusListener = listener
        listener?.onStatusChanged(isRunning, currentAddress())
    }

    private var server: TacticomServer? = null
    private lateinit var ringController: RingController
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null
    private var lastKnownIp: String? = null

    override fun onCreate() {
        super.onCreate()
        ringController = RingController(this)
        ringController.createNotificationChannel()
        createServiceNotificationChannel()
        acquireWakeLock()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires this to be the first thing done in onStartCommand
        // for any service launched in the foreground-service style, every
        // single time it's invoked -- not just on the very first launch.
        // It's a cheap, idempotent call (just updates the existing
        // notification) the rest of the time.
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification(currentAddress()))

        if (intent?.action == RingController.ACTION_STOP_RING) {
            server?.stopActiveRing()
            return START_STICKY
        }

        if (server == null) {
            startServer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        unregisterNetworkCallback()
        stopServer()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Server lifecycle
    // ------------------------------------------------------------------
    private fun startServer() {
        try {
            val newServer = TacticomServer(this, PORT, ringController) { tag, message ->
                Log.i(TAG, "[$tag] $message")
            }
            newServer.start(NanoTimeouts.SOCKET_READ_TIMEOUT_MS, false)
            server = newServer
            isRunning = true
            lastKnownIp = getLocalIpAddress()
            Log.i(TAG, "Server started on port $PORT, reachable at ${currentAddress()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            isRunning = false
        }
        updateNotification()
        statusListener?.onStatusChanged(isRunning, currentAddress())
    }

    private fun stopServer() {
        server?.let {
            it.shutdownCleanup()
            it.stop()
        }
        server = null
        isRunning = false
        statusListener?.onStatusChanged(false, null)
    }

    private fun restartServer() {
        Log.i(TAG, "Network changed -- restarting server so the address stays correct")
        stopServer()
        startServer()
    }

    // ------------------------------------------------------------------
    // Network change detection
    // ------------------------------------------------------------------
    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRestart()
            override fun onLost(network: Network) = scheduleRestart()
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: android.net.NetworkCapabilities,
            ) {
                // A new DHCP lease on the same network (e.g. router reboot)
                // still lands here even though the Network object is the
                // same -- worth checking whether our advertised IP is
                // still accurate.
                val newIp = getLocalIpAddress()
                if (newIp != null && newIp != lastKnownIp) {
                    scheduleRestart()
                }
            }
        }
        networkCallback = callback
        connectivityManager?.registerDefaultNetworkCallback(callback)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try { connectivityManager?.unregisterNetworkCallback(cb) } catch (e: Exception) { /* already gone */ }
        }
        networkCallback = null
    }

    /** Network callbacks can fire in quick bursts (Wi-Fi handoff, DHCP
     * renew) -- debounce so a flurry of events triggers one restart, not
     * five. */
    private fun scheduleRestart() {
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { restartServer() }
        pendingRestart = r
        mainHandler.postDelayed(r, NETWORK_RESTART_DEBOUNCE_MS)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine local IP", e)
        }
        return null
    }

    private fun currentAddress(): String? {
        val ip = lastKnownIp ?: getLocalIpAddress() ?: return null
        return "https://$ip:$PORT"
    }

    // ------------------------------------------------------------------
    // Foreground notification (the persistent "server is running" one --
    // separate from the ring alert notification)
    // ------------------------------------------------------------------
    private fun createServiceNotificationChannel() {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "TACTICOM running",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows while the intercom server is active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildServiceNotification(address: String?): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("TACTICOM is running")
            .setContentText(address ?: "Waiting for network...")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(currentAddress()))
    }

    // ------------------------------------------------------------------
    // Wake lock -- keeps the CPU from suspending while this is running,
    // separate from (and in addition to) the foreground-service exemption
    // from being killed outright.
    // ------------------------------------------------------------------
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TACTICOM::ServerWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L /* 12h safety cap, renewed by re-acquire on restart */)
        }
    }
}

private object NanoTimeouts {
    const val SOCKET_READ_TIMEOUT_MS = 60_000
}
