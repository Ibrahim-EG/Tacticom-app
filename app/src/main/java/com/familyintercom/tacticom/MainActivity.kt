package com.familyintercom.tacticom

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), TacticomService.StatusListener {

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var toggleButton: Button
    private lateinit var batteryButton: Button
    private lateinit var ringtoneButton: Button

    private var service: TacticomService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TacticomService.LocalBinder).getService()
            service?.setStatusListener(this@MainActivity)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* proceed regardless */ }

    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                RingController(this).setCustomRingtoneUri(uri)
                Toast.makeText(this, "Ringtone updated", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        toggleButton = findViewById(R.id.toggleButton)
        batteryButton = findViewById(R.id.batteryButton)
        ringtoneButton = findViewById(R.id.ringtoneButton)

        toggleButton.setOnClickListener {
            if (TacticomService.isRunning) stopServer() else startServer()
        }
        batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        ringtoneButton.setOnClickListener {
            ringtonePickerLauncher.launch(arrayOf("audio/*"))
        }

        requestNotificationPermissionIfNeeded()
        startServer() // launch automatically on open, matching "always-on house intercom"
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TacticomService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.setStatusListener(null)
            unbindService(connection)
            bound = false
        }
    }

    override fun onStatusChanged(running: Boolean, address: String?) {
        runOnUiThread {
            statusText.text = if (running) "Running" else "Stopped"
            addressText.text = address ?: "Waiting for network..."
            toggleButton.text = if (running) "Stop" else "Start"
        }
    }

    private fun startServer() {
        val intent = Intent(this, TacticomService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopServer() {
        stopService(Intent(this, TacticomService::class.java))
        onStatusChanged(false, null)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
        }
    }
}
