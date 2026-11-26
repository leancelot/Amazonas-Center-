package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ExpiryAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ExpiryNotificationManager.onAlarmTriggered(context)
    }
}
