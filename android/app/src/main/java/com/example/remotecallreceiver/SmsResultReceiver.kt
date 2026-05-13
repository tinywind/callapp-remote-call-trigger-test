package com.example.remotecallreceiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1)
        val partLabel = if (partCount > 1) " part ${partIndex + 1}/$partCount" else ""
        val result = smsResultName(resultCode)
        val status = if (resultCode == Activity.RESULT_OK) {
            "SMS$partLabel sent to $number."
        } else {
            "SMS$partLabel send failed for $number: $result."
        }

        context.getSharedPreferences(CallReceiverService.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CallReceiverService.KEY_STATUS, status)
            .apply()
    }

    private fun smsResultName(resultCode: Int): String =
        when (resultCode) {
            Activity.RESULT_OK -> "RESULT_OK"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
            SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
            else -> "RESULT_$resultCode"
        }

    companion object {
        const val ACTION_SMS_SENT = "com.example.remotecallreceiver.action.SMS_SENT"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"
    }
}
