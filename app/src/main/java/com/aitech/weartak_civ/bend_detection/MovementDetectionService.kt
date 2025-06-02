package com.aitech.weartak_civ.bend_detection

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class MovementDetectionService : Service(), SensorEventListener {

    private lateinit var cnnInference: CNNInference
    private val modelFileName = "/Users/ninjasensei/Ascent/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/bend_detection/model/cnn_model.tflite"

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val svmWindow = ArrayDeque<Float>()  // Stores latest SVM values (300 max)
    private val svmThreshold = 5.0f              // change in SVM threshold given "experimentally"
    private val samplingRate = SensorManager.SENSOR_DELAY_FASTEST  // ~50Hz

    override fun onCreate() {
        super.onCreate()

        // Sensor setup
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, samplingRate)
        }

        // CNN Model setup
        cnnInference = CNNInference(applicationContext, modelFileName)
        cnnInference.initializeInterpreter(
            onSuccess = { Log.d("CNN", "Interpreter initialized successfully") },
            onFailure = { e -> Log.e("CNN", "Interpreter failed to initialize: ${e.message}") }
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val svm = sqrt(x * x + y * y + z * z)
        svmWindow.addLast(svm)

        if (svmWindow.size > 300) {
            svmWindow.removeFirst()
        }

        // Motion gating when we have at least 150 samples (~3s of data)
        if (svmWindow.size >= 150) {
            val delta = computeSvmDelta(svmWindow)
            if (delta >= svmThreshold && svmWindow.size >= 300) {
                runInference(svmWindow.takeLast(300).toFloatArray())
            }
        }
    }

    private fun computeSvmDelta(svmData: Collection<Float>): Float {
        return svmData.zipWithNext { a, b -> abs(b - a) }.sum()
    }

    private fun runInference(input: FloatArray) {
        val result = cnnInference.runInference(input)
        val predictedClass = result.indices.maxByOrNull { result[it] } ?: -1

        Log.d("Inference", "Predicted class: $predictedClass, Scores: ${result.joinToString()}")

        // Planning to trigger alerts here
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        cnnInference.closeInterpreter()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
