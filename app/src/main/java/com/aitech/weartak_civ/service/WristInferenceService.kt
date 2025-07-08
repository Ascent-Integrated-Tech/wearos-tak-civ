package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.net.Socket // just for visualization!
import java.util.LinkedList

class WristInferenceService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var model: Module
    private val imuBuffer: LinkedList<FloatArray> = LinkedList()
    private val sequenceLength = 125

    override fun onCreate() {
        super.onCreate()

        // Load the model
        model = Module.load(assetFilePath(this, "smpl.pt"))

        // Set up sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)

        Log.i("WristService", "Inference service started")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val timestamp = System.currentTimeMillis()

        val values = FloatArray(6) { 0f }
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                values[0] = event.values[0]
                values[1] = event.values[1]
                values[2] = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                values[3] = event.values[0]
                values[4] = event.values[1]
                values[5] = event.values[2]
            }
        }

        imuBuffer.add(values)
        if (imuBuffer.size > sequenceLength) imuBuffer.removeFirst()

        if (imuBuffer.size == sequenceLength) {
            val inputData = FloatArray(sequenceLength * 6)
            imuBuffer.forEachIndexed { i, frame ->
                for (j in 0..5) inputData[i * 6 + j] = frame[j]
            }

            val inputTensor = Tensor.fromBlob(inputData, longArrayOf(1, sequenceLength.toLong(), 6))
            val outputTuple = model.forward(IValue.from(inputTensor)).toTuple()

            val pose = outputTuple[0].toTensor().dataAsFloatArray
            // val bend = outputTuple[1].toTensor().dataAsFloatArray[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }
}
