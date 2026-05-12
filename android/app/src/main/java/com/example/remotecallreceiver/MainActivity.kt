package com.example.remotecallreceiver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.messaging.FirebaseMessaging
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ipText: TextView
    private lateinit var statusText: TextView
    private lateinit var portInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var toggleButton: Button
    private val refreshStatus = object : Runnable {
        override fun run() {
            renderReceiverState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        renderReceiverState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val grantedCount = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            setStatus("$grantedCount permission request(s) granted.")
        }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Remote Call Receiver"
            textSize = 22f
        }

        ipText = TextView(this).apply {
            text = "Phone IP: ${findLocalIpv4Address()}"
            textSize = 15f
            setPadding(0, 20, 0, 20)
        }

        portInput = EditText(this).apply {
            hint = "Port"
            setText(CallReceiverService.DEFAULT_PORT.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        tokenInput = EditText(this).apply {
            hint = "Token"
            setText(CallReceiverService.DEFAULT_TOKEN)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val permissionButton = Button(this).apply {
            text = "Grant Permissions"
            setOnClickListener { requestRequiredPermissions() }
        }

        val batteryButton = Button(this).apply {
            text = "Battery Exception"
            setOnClickListener { requestBatteryException() }
        }

        val fcmButton = Button(this).apply {
            text = "Show FCM Token"
            setOnClickListener { requestFcmToken() }
        }

        toggleButton = Button(this).apply {
            text = "Start Receiver"
            setOnClickListener {
                if (isReceiverRunning()) {
                    stopReceiver()
                } else {
                    startReceiver()
                }
            }
        }

        statusText = TextView(this).apply {
            text = "Idle"
            textSize = 15f
            gravity = Gravity.START
            setPadding(0, 20, 0, 0)
        }

        root.addView(title)
        root.addView(ipText)
        root.addView(portInput)
        root.addView(tokenInput)
        root.addView(permissionButton)
        root.addView(batteryButton)
        root.addView(fcmButton)
        root.addView(toggleButton)
        root.addView(statusText)

        setContentView(root)
    }

    private fun startReceiver() {
        val port = portInput.text.toString().toIntOrNull()
        val token = tokenInput.text.toString()

        if (port == null || port !in 1..65535) {
            setStatus("Enter a valid port.")
            return
        }

        if (token.isBlank()) {
            setStatus("Enter a shared token.")
            return
        }

        CallReceiverService.start(this, port, token)
        setStatus("Starting receiver...")
        renderReceiverState()
    }

    private fun stopReceiver() {
        CallReceiverService.stop(this)
        setStatus("Stopping receiver...")
        renderReceiverState()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            permissions += Manifest.permission.CALL_PHONE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        ) {
            permissions += Manifest.permission.ANSWER_PHONE_CALLS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        if (permissions.isEmpty()) {
            setStatus("Required permissions already granted.")
            return
        }

        requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun requestBatteryException() {
        val powerManager = getSystemService(PowerManager::class.java)

        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            setStatus("Battery optimization is already ignored.")
            return
        }

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun requestFcmToken() {
        try {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        setStatus("FCM token unavailable: ${task.exception?.message}")
                        return@addOnCompleteListener
                    }

                    setStatus("FCM token: ${task.result}")
                }
        } catch (error: IllegalStateException) {
            setStatus("FCM is not configured. Add android/app/google-services.json.")
        }
    }

    private fun renderReceiverState() {
        toggleButton.text = if (isReceiverRunning()) "Stop Receiver" else "Start Receiver"
        statusText.text = getSharedPreferences(CallReceiverService.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(CallReceiverService.KEY_STATUS, "Idle")
    }

    private fun isReceiverRunning(): Boolean =
        getSharedPreferences(CallReceiverService.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(CallReceiverService.KEY_RUNNING, false)

    private fun hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun findLocalIpv4Address(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "unknown"
                    }
                }
            }

            "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 1001
    }
}
