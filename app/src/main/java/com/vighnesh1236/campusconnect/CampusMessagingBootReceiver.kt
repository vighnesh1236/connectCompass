package com.vighnesh1236.campusconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CampusMessagingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CampusMessagingService.ensureRunning(context)
        }
    }
}
