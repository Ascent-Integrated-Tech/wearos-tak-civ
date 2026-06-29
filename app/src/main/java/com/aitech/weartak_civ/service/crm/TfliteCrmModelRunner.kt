/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service.crm

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TfliteCrmModelRunner(
    context: Context,
    assetName: String = ASSET_NAME
) : CrmModelRunner {
    private val lock = ReentrantLock()
    private val interpreter = Interpreter(
        loadModel(context, assetName),
        Interpreter.Options().setNumThreads(1)
    )

    override fun run(scaledSamples: FloatArray): Float {
        require(scaledSamples.size == CrmPpgPreprocessor.TARGET_SAMPLES) {
            "CRM input must contain ${CrmPpgPreprocessor.TARGET_SAMPLES} samples"
        }
        val input = Array(1) { Array(CrmPpgPreprocessor.TARGET_SAMPLES) { FloatArray(1) } }
        for (i in scaledSamples.indices) {
            input[0][i][0] = scaledSamples[i]
        }
        val output = Array(1) { FloatArray(1) }
        return lock.withLock {
            interpreter.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        }
    }

    override fun close() {
        lock.withLock { interpreter.close() }
    }

    companion object {
        const val ASSET_NAME = "crm_model100.tflite"

        private fun loadModel(context: Context, assetName: String): MappedByteBuffer {
            context.assets.openFd(assetName).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
        }
    }
}
