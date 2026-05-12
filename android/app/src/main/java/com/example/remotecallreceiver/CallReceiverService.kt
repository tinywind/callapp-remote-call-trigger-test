package com.example.remotecallreceiver

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.TelecomManager
import java.net.Inet4Address
import java.net.NetworkInterface

class CallReceiverService : Service() {
    private var callServer: CallHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopReceiver()
                stopSelf()
            }
            else -> startReceiver(
                port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT,
                token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty().ifBlank { DEFAULT_TOKEN }
            )
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopReceiver()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startReceiver(port: Int, token: String) {
        startInForeground("Starting receiver on port $port...")
        callServer?.stop()

        val server = CallHttpServer(
            port = port,
            token = token,
            hasCallPermission = ::hasCallPermission,
            placeCall = ::placeCall,
            onStatus = ::setStatus
        )

        callServer = server
        server.start()
        setRunning(true)
        setStatus("Listening on http://${findLocalIpv4Address()}:$port/call")
        updateNotification("Listening on port $port")
    }

    private fun stopReceiver() {
        callServer?.stop()
        callServer = null
        setRunning(false)
        setStatus("Receiver stopped.")
    }

    private fun placeCall(number: String) {
        if (!hasCallPermission()) {
            setStatus("Phone permission is required before placing calls.")
            return
        }

        try {
            val telecomManager = getSystemService(TelecomManager::class.java)
            telecomManager.placeCall(Uri.fromParts("tel", number, null), Bundle.EMPTY)
            setStatus("TelecomManager.placeCall requested for $number.")
        } catch (error: RuntimeException) {
            setStatus("Failed to place call: ${error.message}")
        }
    }

    private fun hasCallPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground(contentText: String) {
        val notification = buildNotification(contentText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CallReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Remote Call Receiver")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Remote call receiver",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun setStatus(message: String) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, message)
            .apply()
    }

    private fun setRunning(running: Boolean) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, running)
            .apply()
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

    companion object {
        const val ACTION_STOP = "com.example.remotecallreceiver.action.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val DEFAULT_PORT = 8088
        const val DEFAULT_TOKEN = "dev-token"
        const val PREFERENCES_NAME = "receiver-state"
        const val KEY_STATUS = "status"
        const val KEY_RUNNING = "running"

        private const val NOTIFICATION_CHANNEL_ID = "remote-call-receiver"
        private const val NOTIFICATION_ID = 1002
    }
}
