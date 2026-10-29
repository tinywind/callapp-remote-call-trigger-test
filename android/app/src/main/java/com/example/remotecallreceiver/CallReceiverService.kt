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
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telephony.SmsManager
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

class CallReceiverService : Service() {
    private var callServer: CallHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty().ifBlank { savedToken(this) }
        if (intent?.action != ACTION_STOP && !CallTokenPolicy.isConfigured(token)) {
            stopReceiver()
            setStatus("Configure a non-default shared token before starting the receiver.")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopReceiver()
                stopSelf()
            }
            ACTION_HANG_UP -> {
                val port = intent.getIntExtra(EXTRA_PORT, savedPort(this))

                ensureReceiverRunning(port = port, token = token)
                hangUpCall()
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, savedPort(this)) ?: savedPort(this)
                val number = intent?.getStringExtra(EXTRA_NUMBER)?.trim().orEmpty()

                startReceiver(port = port, token = token)

                if (number.isNotBlank()) {
                    placeCall(number)
                }
            }
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
        saveConfig(port, token)

        val server = CallHttpServer(
            port = port,
            token = token,
            hasCallPermission = ::hasCallPermission,
            hasHangUpPermission = ::hasHangUpPermission,
            hasSendSmsPermission = ::hasSendSmsPermission,
            hasReadSmsPermission = ::hasReadSmsPermission,
            placeCall = ::placeCall,
            hangUpCall = ::hangUpCall,
            sendSms = ::sendSms,
            backupSms = ::backupSms,
            onStatus = ::setStatus
        )

        callServer = server
        server.start()
        setRunning(true)
        setStatus("Listening on http://${findLocalIpv4Address()}:$port/call")
        updateNotification("Listening on port $port")
    }

    private fun ensureReceiverRunning(port: Int, token: String) {
        if (callServer == null) {
            startReceiver(port = port, token = token)
            return
        }

        saveConfig(port, token)
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
        } catch (error: SecurityException) {
            setStatus("Failed to place call: ${error.message}")
        } catch (error: RuntimeException) {
            setStatus("Failed to place call: ${error.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun hangUpCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setStatus("Hang up requires Android 9 or newer.")
            return false
        }

        if (!hasHangUpPermission()) {
            setStatus("Answer phone calls permission is required before hanging up calls.")
            return false
        }

        return try {
            val ended = getSystemService(TelecomManager::class.java).endCall()
            setStatus(
                if (ended) {
                    "TelecomManager.endCall ended the active call."
                } else {
                    "TelecomManager.endCall found no active call to end."
                }
            )
            ended
        } catch (error: SecurityException) {
            setStatus("Failed to hang up call: ${error.message}")
            false
        } catch (error: RuntimeException) {
            setStatus("Failed to hang up call: ${error.message}")
            false
        }
    }

    private fun hasCallPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun hasHangUpPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    private fun sendSms(number: String, message: String) {
        if (!hasSendSmsPermission()) {
            setStatus("Send SMS permission is required before sending messages.")
            return
        }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>()
            val requestCodeBase = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

            parts.forEachIndexed { index, _ ->
                sentIntents += createSmsSentIntent(
                    number = number,
                    partIndex = index,
                    partCount = parts.size,
                    requestCode = requestCodeBase + index
                )
            }

            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(number, null, message, sentIntents.firstOrNull(), null)
            }

            setStatus("SMS send requested for $number. Waiting for radio result.")
        } catch (error: RuntimeException) {
            setStatus("Failed to send SMS: ${error.message}")
        }
    }

    private fun createSmsSentIntent(
        number: String,
        partIndex: Int,
        partCount: Int,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(this, SmsResultReceiver::class.java).apply {
            action = SmsResultReceiver.ACTION_SMS_SENT
            putExtra(SmsResultReceiver.EXTRA_NUMBER, number)
            putExtra(SmsResultReceiver.EXTRA_PART_INDEX, partIndex)
            putExtra(SmsResultReceiver.EXTRA_PART_COUNT, partCount)
        }

        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun backupSms(): JSONObject {
        if (!hasReadSmsPermission()) {
            setStatus("Read SMS permission is required before backing up messages.")
            return JSONObject()
                .put("count", 0)
                .put("messages", JSONArray())
        }

        val messages = JSONArray()
        val projection = arrayOf("_id", "thread_id", "address", "person", "date", "date_sent", "read", "status", "type", "body", "service_center")

        contentResolver.query(
            Uri.parse("content://sms"),
            projection,
            null,
            null,
            "date DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                messages.put(
                    JSONObject()
                        .put("id", cursor.getLongValue("_id"))
                        .put("threadId", cursor.getLongValue("thread_id"))
                        .put("address", cursor.getStringValue("address"))
                        .put("person", cursor.getStringValue("person"))
                        .put("date", cursor.getLongValue("date"))
                        .put("dateSent", cursor.getLongValue("date_sent"))
                        .put("read", cursor.getIntValue("read"))
                        .put("status", cursor.getIntValue("status"))
                        .put("type", cursor.getIntValue("type"))
                        .put("typeName", smsTypeName(cursor.getIntValue("type")))
                        .put("body", cursor.getStringValue("body"))
                        .put("serviceCenter", cursor.getStringValue("service_center"))
                )
            }
        }

        setStatus("SMS backup exported ${messages.length()} message(s).")

        return JSONObject()
            .put("exportedAt", System.currentTimeMillis())
            .put("count", messages.length())
            .put("messages", messages)
    }

    private fun hasSendSmsPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasReadSmsPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

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

    private fun saveConfig(port: Int, token: String) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PORT, port)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    private fun findLocalIpv4Address(): String {
        return try {
            val siteLocalAddresses = mutableListOf<String>()
            val fallbackAddresses = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress ?: continue

                        if (address.isSiteLocalAddress && networkInterface.name.startsWith("wlan")) {
                            return hostAddress
                        }

                        if (address.isSiteLocalAddress) {
                            siteLocalAddresses += hostAddress
                        } else {
                            fallbackAddresses += hostAddress
                        }
                    }
                }
            }

            siteLocalAddresses.firstOrNull() ?: fallbackAddresses.firstOrNull() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun smsTypeName(type: Int): String =
        when (type) {
            1 -> "inbox"
            2 -> "sent"
            3 -> "draft"
            4 -> "outbox"
            5 -> "failed"
            6 -> "queued"
            else -> "unknown"
        }

    private fun Cursor.getStringValue(columnName: String): String =
        getColumnIndex(columnName)
            .takeIf { it >= 0 }
            ?.let { getString(it) }
            .orEmpty()

    private fun Cursor.getLongValue(columnName: String): Long =
        getColumnIndex(columnName)
            .takeIf { it >= 0 }
            ?.let { getLong(it) }
            ?: 0L

    private fun Cursor.getIntValue(columnName: String): Int =
        getColumnIndex(columnName)
            .takeIf { it >= 0 }
            ?.let { getInt(it) }
            ?: 0

    companion object {
        const val ACTION_STOP = "com.example.remotecallreceiver.action.STOP"
        const val ACTION_HANG_UP = "com.example.remotecallreceiver.action.HANG_UP"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_NUMBER = "number"
        const val DEFAULT_PORT = 8088
        const val DEFAULT_TOKEN = ""
        const val PREFERENCES_NAME = "receiver-state"
        const val KEY_STATUS = "status"
        const val KEY_RUNNING = "running"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"

        private const val NOTIFICATION_CHANNEL_ID = "remote-call-receiver"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context, port: Int = savedPort(context), token: String = savedToken(context), number: String? = null): Boolean {
            if (!CallTokenPolicy.isConfigured(token)) {
                setStoredStatus(context, "Configure a non-default shared token before starting the receiver.")
                return false
            }
            val intent = Intent(context, CallReceiverService::class.java).apply {
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_TOKEN, token)

                if (!number.isNullOrBlank()) {
                    putExtra(EXTRA_NUMBER, number)
                }
            }

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (error: RuntimeException) {
                setStoredStatus(context, "Failed to start receiver service: ${error.message}")
                false
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallReceiverService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun hangUp(context: Context, port: Int = savedPort(context), token: String = savedToken(context)): Boolean {
            if (!CallTokenPolicy.isConfigured(token)) {
                setStoredStatus(context, "Configure a non-default shared token before starting the receiver.")
                return false
            }
            val intent = Intent(context, CallReceiverService::class.java).apply {
                action = ACTION_HANG_UP
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_TOKEN, token)
            }

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (error: RuntimeException) {
                setStoredStatus(context, "Failed to start hang up request: ${error.message}")
                false
            }
        }

        fun savedPort(context: Context): Int =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PORT, DEFAULT_PORT)

        fun savedToken(context: Context): String =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, DEFAULT_TOKEN)
                .orEmpty()
                .takeIf(CallTokenPolicy::isConfigured)
                .orEmpty()

        fun setStoredStatus(context: Context, message: String) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATUS, message)
                .apply()
        }
    }
}
