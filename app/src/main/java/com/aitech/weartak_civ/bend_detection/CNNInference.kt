package com.aitech.weartak_civ.bend_detection

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CNNInference(private val context: Context, private val modelFileName: String) {

    companion object {
        private const val INPUT_SIZE = 300 // (before FFT) input size for frequency domain
        private const val OUTPUT_SIZE = 4  // Number of output classes
    }

    private lateinit var interpreter: Interpreter

    fun initializeInterpreter(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, modelFileName)
            val options = Interpreter.Options()
            interpreter = Interpreter(modelBuffer, options)
            onSuccess()
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    fun runInference(inputData: FloatArray): FloatArray {
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * 4).apply {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().put(inputData)
        }

        val outputBuffer = ByteBuffer.allocateDirect(OUTPUT_SIZE * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        return FloatArray(OUTPUT_SIZE).apply {
            outputBuffer.asFloatBuffer().get(this)
        }
    }

    fun closeInterpreter() {
        if (::interpreter.isInitialized) {
            interpreter.close()
        }
    }
}


