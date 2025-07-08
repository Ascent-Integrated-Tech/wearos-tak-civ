// ActivityRecognitionService.kt
package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import java.io.File

class ActivityRecognitionService : Service() {
    companion object {
        private const val TAG = "WearTAK-ActRecService"
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val ACTION_RECOGNITION = "com.aitech.weartak_civ.ACTION_RECOGNITION"
        private const val NOTIF_ID = 192
        private const val LOG_FILENAME = "/sdcard/Download/activity_detection.log"
        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }

    private val logFile by lazy { File(LOG_FILENAME) }
    private lateinit var client: ActivityRecognitionClient
    private lateinit var callbackIntent: PendingIntent
    private val repo: DataRepository
        get() = MainActivity.dataRepository

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ActivityRecognitionService onCreate")

        client = ActivityRecognition.getClient(this)
        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW
        )
        // Instead of a BroadcastReceiver, send the Intent back to this Service:
        val intent = Intent(this, ActivityRecognitionService::class.java).setAction(ACTION_RECOGNITION)

        callbackIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            client.requestActivityUpdates(UPDATE_INTERVAL_MS, callbackIntent)
                .addOnSuccessListener { Log.i(TAG, "Activity updates requested every $UPDATE_INTERVAL_MS ms") }
                .addOnFailureListener { Log.e(TAG, "Failed to request activity updates", it) }
        } else {
            Log.w(TAG, "Cannot request activity updates: permission not granted")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //val line = "ActivityRecognitionService onStartCommand with intent action (${intent?.action})"
        //Log.i(TAG, line)
        //writeToLogFileLocation(logFile, line)

        // Handle every ACTION_RECOGNITION intent
        if (intent?.action == ACTION_RECOGNITION &&
            ActivityRecognitionResult.hasResult(intent)) {

            val result = ActivityRecognitionResult.extractResult(intent)
            val act = result?.mostProbableActivity
            val type = act?.type ?: DetectedActivity.UNKNOWN
            val name = when (type) {
                DetectedActivity.RUNNING    -> "RUNNING"
                DetectedActivity.WALKING    -> "WALKING"
                DetectedActivity.TILTING    -> "TILTING"
                DetectedActivity.STILL      -> "STILL"
                DetectedActivity.ON_BICYCLE -> "BICYCLING"
                DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                DetectedActivity.ON_FOOT    -> "ON_FOOT"
                DetectedActivity.UNKNOWN    -> "UNKNOWN"
                else                        -> "UNKNOWN"
            }
            // TODO: Need to look at confidence and do sthg if confidence is less than 50?
            val line = "Detected $name  confidence=${act?.confidence ?: 100}"
            Log.i(TAG, line)
            //writeToLogFileLocation(logFile, line)

            // ② Persist in repo (backend-only)
            CoroutineScope(Dispatchers.IO).launch {
                repo.storeLatestActivityRecognized(name)
                repo.storeLatestActivityConfidence(act?.confidence ?: 100)
            }
            return START_NOT_STICKY
        }

        return START_STICKY          // keep service alive
    }

    override fun onDestroy() {
        super.onDestroy()

        if (!isRunning.getAndSet(false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        client.removeActivityUpdates(callbackIntent).addOnSuccessListener { callbackIntent.cancel() }.addOnFailureListener { Log.e(TAG, "Failed to remove activity updates", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}