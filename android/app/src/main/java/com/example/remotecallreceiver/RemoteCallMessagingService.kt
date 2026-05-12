package com.example.remotecallreceiver

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class RemoteCallMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val action = data["action"]

        if (action !in setOf(ACTION_CALL, ACTION_HANG_UP)) {
            return
        }

        val expectedToken = CallReceiverService.savedToken(this)
        val requestToken = data["token"].orEmpty()

        if (requestToken != expectedToken) {
            CallReceiverService.setStoredStatus(this, "Rejected FCM call request: invalid token.")
            return
        }

        val port = data["port"]?.toIntOrNull() ?: CallReceiverService.savedPort(this)

        if (action == ACTION_HANG_UP) {
            CallReceiverService.hangUp(
                context = this,
                port = port,
                token = expectedToken
            )
            return
        }

        val number = data["number"].orEmpty().trim()

        if (number.isBlank()) {
            CallReceiverService.setStoredStatus(this, "Rejected FCM call request: missing number.")
            return
        }

        val started = CallReceiverService.start(
            context = this,
            port = port,
            token = expectedToken,
            number = number
        )

        if (started) {
            CallReceiverService.setStoredStatus(this, "FCM call request accepted.")
        }
    }

    override fun onNewToken(token: String) {
        CallReceiverService.setStoredStatus(this, "FCM token refreshed.")
    }

    private companion object {
        const val ACTION_CALL = "call"
        const val ACTION_HANG_UP = "hangup"
    }
}
