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
import java.io.File

class ActivityRecognitionService : Service() {
    companion object {
        private const val TAG = "WearTAK-ActRecService"
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val ACTION_RECOGNITION = "com.aitech.weartak_civ.ACTION_RECOGNITION"
        private const val NOTIF_ID = 192
        private const val LOG_FILE    = "activity_detection.log"
        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }

    private val logFile by lazy { File(filesDir, LOG_FILE) }
    private lateinit var client: ActivityRecognitionClient
    private lateinit var callbackIntent: PendingIntent
    private val repo: DataRepository
        get() = MainActivity.dataRepository

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ActivityRecognitionService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW
        )

        // init the Google Activity recognition client
        client = ActivityRecognition.getClient(this)
        val intent = Intent(this, ActivityRecognitionService::class.java).setAction(ACTION_RECOGNITION)
        callbackIntent = PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // TODO: Remove permissions in the service and make the Manager handle this.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            client.requestActivityUpdates(UPDATE_INTERVAL_MS, callbackIntent)
                .addOnSuccessListener { Log.i(TAG, "ActivityRecognitionService Activity updates requested") }
                .addOnFailureListener { Log.e(TAG, "ActivityRecognitionService Failed to request updates", it) }
        } else {
            Log.w(TAG, "ActivityRecognitionService Cannot request activity updates: permission not granted")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ① Always handle every ACTION_RECOGNITION intent
        Log.d(TAG, "ActivityRecognitionService onStartCommand()")
        if (intent?.action == ACTION_RECOGNITION &&
            ActivityRecognitionResult.hasResult(intent)) {

            val result = ActivityRecognitionResult.extractResult(intent)!!
            val act = result.mostProbableActivity
            val name = when (act.type) {
                DetectedActivity.RUNNING    -> "RUNNING"
                DetectedActivity.ON_BICYCLE -> "BICYCLING"
                DetectedActivity.WALKING    -> "WALKING"
                DetectedActivity.TILTING    -> "TILTING"
                DetectedActivity.STILL      -> "STILL"
                DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                DetectedActivity.ON_FOOT    -> "ON_FOOT"
                DetectedActivity.UNKNOWN    -> "UNKNOWN"
                else                        -> "OTHER"
            }
            val line = "ActivityRecognitionService Detected [$name] confidence=${act.confidence}"
            Log.i(TAG, line)

            // ② Persist in repo (backend-only)
            CoroutineScope(Dispatchers.IO).launch {
                repo.storeLatestActivityRecognized(name)
                repo.storeLatestActivityConfidence(act.confidence)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ActivityRecognitionService onDestroy()")
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            client.removeActivityUpdates(callbackIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}