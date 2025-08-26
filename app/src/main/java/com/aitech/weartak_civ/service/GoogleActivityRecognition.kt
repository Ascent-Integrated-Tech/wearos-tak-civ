package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import java.util.concurrent.atomic.AtomicBoolean

class GoogleActivityRecognition : Service() {

    companion object {
        private const val TAG = "GoogleActivityRecognition"
        private const val UPDATE_INTERVAL_MS = 3_000L  // Update every 3 seconds for more frequent detection
        private const val ACTION_ACTIVITY_RECOGNITION = "com.aitech.weartak_civ.ACTION_ACTIVITY_RECOGNITION"
        private const val ACTION_ACTIVITY_BROADCAST = "com.aitech.weartak_civ.ACTIVITY_BROADCAST"
        private const val EXTRA_ACTIVITY_TYPE = "activity_type"
        private const val EXTRA_ACTIVITY_CONFIDENCE = "activity_confidence"
        private const val NOTIFICATION_ID = 193
        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var pendingIntent: PendingIntent

    override fun onCreate() {
        super.onCreate()

        isRunning.set(true)
        Log.i(TAG, "GoogleActivityRecognition service onCreate called")

        // Initialize the Activity Recognition client
        activityRecognitionClient = ActivityRecognition.getClient(this)

        // Create the pending intent for activity updates
        val intent = Intent(this, GoogleActivityRecognition::class.java).apply {
            action = ACTION_ACTIVITY_RECOGNITION
        }

        pendingIntent = PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Request activity updates
        requestActivityUpdates()
    }

    private fun requestActivityUpdates() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "ACTIVITY_RECOGNITION permission not granted")
            stopSelf()
            return
        }

        Log.i(TAG, "Requesting activity updates every $UPDATE_INTERVAL_MS ms")

        activityRecognitionClient.requestActivityUpdates(UPDATE_INTERVAL_MS, pendingIntent)
            .addOnSuccessListener {
                Log.i(TAG, "✅ Successfully registered for Google Activity Recognition updates")
                Log.i(TAG, "📱 Monitoring for: IN_VEHICLE, ON_BICYCLE, ON_FOOT, RUNNING, STILL, TILTING, WALKING, UNKNOWN")
                Log.i(TAG, "🔄 Service will continue running and receive updates every ${UPDATE_INTERVAL_MS / 1000} seconds")

                // Start a periodic heartbeat to show continuous monitoring
                startHeartbeat()
                
                // Emit an initial STILL event
                broadcastActivityUpdate(DetectedActivity.STILL, 90)
                Log.i(TAG, "🎯 Initial STILL activity broadcasted (confidence: 90%)")
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "❌ Failed to register for activity updates", exception)
                stopSelf()
            }
    }

    private fun startHeartbeat() {
        // Send a heartbeat every 10 seconds to show the service is continuously monitoring
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isRunning.get()) {
                Log.i(TAG, "💓 Service heartbeat: Continuously monitoring for activity updates...")
                startHeartbeat() // Schedule next heartbeat
            }
        }, 10000) // 10 seconds
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACTIVITY_RECOGNITION &&
            ActivityRecognitionResult.hasResult(intent)) {

            val result = ActivityRecognitionResult.extractResult(intent)
            result?.let { activityResult ->
                // Process all probable activities, not just the most probable one
                val probableActivities = activityResult.probableActivities

                Log.i(TAG, "🔄 === Activity Recognition Update ===")
                Log.i(TAG, "📊 Number of probable activities: ${probableActivities.size}")

                // Sort activities by confidence (highest first)
                val sortedActivities = probableActivities.sortedByDescending { it.confidence }

                sortedActivities.forEach { activity ->
                    val activityName = getActivityName(activity.type)
                    val confidence = activity.confidence

                    // Log with emoji indicators for different confidence levels
                    val confidenceEmoji = when {
                        confidence >= 80 -> "🟢"  // High confidence
                        confidence >= 60 -> "🟡"  // Medium confidence
                        else -> "🔴"              // Low confidence
                    }

                    Log.i(TAG, "$confidenceEmoji $activityName: $confidence% confidence")
                }

                // Get the most probable activity
                val mostProbable = activityResult.mostProbableActivity
                if (mostProbable != null) {
                    val activityType = mostProbable.type
                    val confidence = mostProbable.confidence

                    // Broadcast activity update to MovementDetectionService
                    broadcastActivityUpdate(activityType, confidence)

                    // Log the most probable activity prominently
                    val activityName = getActivityName(mostProbable.type)
                    Log.i(TAG, "🎯 MOST PROBABLE: $activityName ($confidence% confidence)")
                }

                Log.i(TAG, "🔄 === End Update ===\n")
            }
        }

        // Always return START_STICKY to keep the service running continuously
        return START_STICKY
    }

    private fun getActivityName(activityType: Int): String {
        return when (activityType) {
            DetectedActivity.IN_VEHICLE -> "🚗 IN_VEHICLE"
            DetectedActivity.ON_BICYCLE -> "🚴 ON_BICYCLE"
            DetectedActivity.ON_FOOT -> "👟 ON_FOOT"
            DetectedActivity.RUNNING -> "🏃 RUNNING"
            DetectedActivity.STILL -> "⏸️ STILL"
            DetectedActivity.TILTING -> "📱 TILTING"
            DetectedActivity.WALKING -> "🚶 WALKING"
            DetectedActivity.UNKNOWN -> "❓ UNKNOWN"
            else -> "❓ UNKNOWN_ACTIVITY_$activityType"
        }
    }

    private fun createNotification(): android.app.Notification {
        val channelId = "google_activity_recognition_channel"

        // Create notification channel for Android 8.0+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Google Activity Recognition",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitoring device activity using Google Play Services"
            }

            val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("Google Activity Recognition")
            .setContentText("Monitoring device activity every ${UPDATE_INTERVAL_MS / 1000} seconds")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    override fun onDestroy() {
        super.onDestroy()

        if (!isRunning.getAndSet(false)) return

        Log.i(TAG, "GoogleActivityRecognition service onDestroy called")

        // Remove activity updates
        if (::activityRecognitionClient.isInitialized && ::pendingIntent.isInitialized) {
            activityRecognitionClient.removeActivityUpdates(pendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "✅ Successfully unregistered from Google Activity Recognition updates")
                    pendingIntent.cancel()
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "❌ Failed to unregister from activity updates", exception)
                }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Broadcast activity update to MovementDetectionService
    private fun broadcastActivityUpdate(activityType: Int, confidence: Int) {
        val broadcastIntent = Intent(ACTION_ACTIVITY_BROADCAST).apply {
            putExtra(EXTRA_ACTIVITY_TYPE, activityType)
            putExtra(EXTRA_ACTIVITY_CONFIDENCE, confidence)
        }

        try {
            sendBroadcast(broadcastIntent)
            Log.d(TAG, "📡 Broadcasted activity: $activityType with ${confidence}% confidence")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to broadcast activity update", e)
        }
    }
}