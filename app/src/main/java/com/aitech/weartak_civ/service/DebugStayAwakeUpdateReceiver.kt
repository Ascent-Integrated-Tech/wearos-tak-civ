package com.aitech.weartak_civ.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.R

class DebugStayAwakeUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = DebugStayAwakePreferences(context)
        if (!preferences.shouldResumeAfterUpdate()) return
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, DebugStayAwakeService::class.java)
            )
        } catch (error: SecurityException) {
            reportFailure(context, preferences, error)
        } catch (error: IllegalStateException) {
            reportFailure(context, preferences, error)
        }
    }

    private fun reportFailure(
        context: Context,
        preferences: DebugStayAwakePreferences,
        error: RuntimeException
    ) {
        preferences.recordSession(false)
        Log.e("WearTAK-DebugStayAwake", "Cannot resume Stay awake after update", error)
        Toast.makeText(context, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
    }
}
