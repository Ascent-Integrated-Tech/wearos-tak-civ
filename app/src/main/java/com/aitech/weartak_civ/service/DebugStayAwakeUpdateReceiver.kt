package com.aitech.weartak_civ.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugStayAwakeUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)) return
        DebugStayAwakeService.restoreIfEnabled(context)
    }
}
