/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.min

object AudioOnnxInferenceUtils {

    fun pcm16ToFloat(pcm: ShortArray): FloatArray =
        FloatArray(pcm.size) { index -> pcm[index] / 32768f }

    fun forceOneSecond(
        x: FloatArray,
        inputSr: Int,
        targetSr: Int,
        targetSamples: Int
    ): FloatArray {
        val resampled = if (inputSr == targetSr) x else linearResample(x, inputSr, targetSr)
        return forceExactLength(resampled, targetSamples)
    }

    fun runModelOutputVector(
        env: OrtEnvironment,
        session: OrtSession,
        data: FloatArray
    ): List<Float> {
        val shape = longArrayOf(1, data.size.toLong())
        val inputName = session.inputNames.first()
        val inputBuffer = FloatBuffer.wrap(data)

        OnnxTensor.createTensor(env, inputBuffer, shape).use { input ->
            session.run(mapOf(inputName to input)).use { results ->
                val output = results[0] as OnnxTensor
                val outBuf = output.floatBuffer
                outBuf.rewind()
                return List(outBuf.remaining()) { outBuf.get() }
            }
        }
    }

    private fun linearResample(x: FloatArray, inSr: Int, outSr: Int): FloatArray {
        if (x.isEmpty()) return FloatArray(0)
        val outLen = ((x.size.toLong() * outSr) / inSr).toInt().coerceAtLeast(1)
        val y = FloatArray(outLen)
        val scale = inSr.toFloat() / outSr.toFloat()
        for (i in 0 until outLen) {
            val src = i * scale
            val idx = src.toInt()
            val frac = src - idx
            val a = x[min(idx, x.size - 1)]
            val b = x[min(idx + 1, x.size - 1)]
            y[i] = a + (b - a) * frac
        }
        return y
    }

    private fun forceExactLength(x: FloatArray, n: Int): FloatArray {
        if (x.size == n) return x
        val y = FloatArray(n)
        val copyN = min(x.size, n)
        System.arraycopy(x, 0, y, 0, copyN)
        return y
    }
}
