package com.example.remotecallreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val started = CallReceiverService.start(context)
                val status = if (started) {
                    "Receiver service start requested from ${intent.action}."
                } else {
                    "Receiver service could not start from ${intent.action}."
                }
                CallReceiverService.setStoredStatus(context, status)
            }
        }
    }
}
