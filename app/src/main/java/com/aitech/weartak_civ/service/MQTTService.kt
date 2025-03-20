package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.presentation.MainActivity
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import javax.net.ssl.SSLSocketFactory

class MQTTService : Service() {

    companion object {
        private val TAG = "WearTAK-" + MQTTService::class.java.simpleName
        private var latestMessage: String? = null
        private var lastUid: String? = null
        private var lastType: String? = null
        private var lastBehaviour: String? = null
        var lastPeriod: String? = CotDispatchService.currReportingIntervalInSecs.toString()

        fun getLatestMessage(): String? = latestMessage
        fun getLastUid(): String? = lastUid
        fun getLastType(): String? = lastType
        fun getLastBehaviour(): String? = lastBehaviour
        //fun getLastPeriod(): String? = lastPeriod

        fun saveReceivedMessage(uid: String?, type: String?, behaviour: String?, period: String?) {
            lastUid = uid
            lastType = type
            lastBehaviour = behaviour
            lastPeriod = period
            Log.d(TAG, "State updated - UID: $uid, Type: $type, Behaviour: $behaviour, Period: $period")

            if (period != null) {
                CotDispatchService.currReportingIntervalInSecs = period.toInt()
                CotDispatchService.reportingUpdateIntervalSecs = period.toInt()
            }
        }
    }

    private val BROKER_URL = "ssl://helene.ascentapi.com:8883" // Use SSL/TLS
    private val USERNAME = "wearos"
    private val PASSWORD = "112358WEAR!"

    private var client: MqttClient? = null
    private lateinit var uid: String
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        uid = intent?.getStringExtra("uid") ?: MainActivity.device_uid
        startForegroundService()
        connectAndSubscribe(uid)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    private fun startForegroundService() {
        val channelId = "MQTTServiceChannel"
        val channelName = "MQTT Background Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("MQTT Service Running")
            .setContentText("Listening for messages...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    private fun connectAndSubscribe(uid: String) {
        val clientId = "AndroidClient-$uid"
        val topic = "wearos/$uid"

        try {
            if (client?.isConnected == true) return

            client = MqttClient(BROKER_URL, clientId, MemoryPersistence()).apply {
                setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.e(TAG, "Connection lost! Retrying...")
                        retryConnection(uid)
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val payload = message.payload.toString(Charsets.UTF_8)
                        Log.d(TAG, "Received message on $topic: $payload")
                        processIncomingMessage(payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken) {
                        Log.d(TAG, "Message delivered")
                    }
                })
            }

            val options = MqttConnectOptions().apply {
                userName = USERNAME
                password = PASSWORD.toCharArray()
                socketFactory = SSLSocketFactory.getDefault()
                isAutomaticReconnect = true
                isCleanSession = true
            }

            client?.connect(options)
            Log.d(TAG, "Connected to MQTT Broker")
            client?.subscribe(topic, 2) // QoS 2 (Exactly once)
            Log.d(TAG, "Subscribed to topic: $topic")

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to MQTT: ${e.message}")
            retryConnection(uid)
        }
    }

    private fun retryConnection(uid: String) {
        handler.postDelayed({ connectAndSubscribe(uid) }, 5000) // Retry after 5 sec
    }

    private fun processIncomingMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val receivedUid = json.optString("uid", null)
            val type = json.optString("type", null)
            val behaviour = json.optString("behaviour", null)
            val period = json.optString("period", null)

            saveReceivedMessage(receivedUid, type, behaviour, period)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON message: ${e.message}")
        }
    }

    private fun disconnect() {
        try {
            client?.takeIf { it.isConnected }?.disconnect()
            Log.d(TAG, "MQTT Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting MQTT: ${e.message}")
        }
    }
}