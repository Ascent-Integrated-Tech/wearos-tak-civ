package com.aitech.weartak_civ.receiver

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aitech.weartak_civ.presentation.MainActivity

class WearTakInitOnUpdate : BroadcastReceiver() {
    companion object {
        private val TAG = "WearTAK-" + "InitOnUpdate"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "onReceive of WearTAK app update w/ action ${intent?.action}")
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent?.action)) {
            try {
                Thread.sleep(5000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

            if (!isActivityRunning(MainActivity::class.java, context!!)) {
                Log.e(TAG, "Starting the main activity on an update")
                val bootIntent = Intent(context, MainActivity::class.java)
                bootIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(bootIntent)
            } else {
                Log.e(TAG, "Activity is already running. Do not start one")
            }
        }
    }

    fun isActivityRunning(activityClass: Class<*>, currContext: Context): Boolean {
        val activityManager = currContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = activityManager.getRunningTasks(Int.MAX_VALUE)

        for (task in tasks) {
            if (activityClass.canonicalName.equals(task.baseActivity?.className ?: "null", ignoreCase = true)) {
                Log.d(TAG, "isActivityRunning returned true")
                return true
            }
        }

        return false
    }
}