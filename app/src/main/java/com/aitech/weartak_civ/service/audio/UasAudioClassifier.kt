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
import android.content.Context
import android.util.Log
import com.aitech.weartak_civ.data.audio.AudioClassificationResult
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.round

class UasAudioClassifier(
    private val context: Context,
    private val logTag: String,
    private val assetName: String,
    private val labels: List<String>,
    private val targetSr: Int = 44_100,
    private val targetSamples: Int = 44_100
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = ReentrantLock()
    private var session: OrtSession? = null

    fun classifyPcm16(audioPcm: ShortArray, sampleRateHz: Int): AudioClassificationResult? {
        val activeSession = ensureSession() ?: return null
        val modelInput = AudioOnnxInferenceUtils.forceOneSecond(
            x = AudioOnnxInferenceUtils.pcm16ToFloat(audioPcm),
            inputSr = sampleRateHz,
            targetSr = targetSr,
            targetSamples = targetSamples
        )

        return try {
            val scores = lock.withLock { AudioOnnxInferenceUtils.runModelOutputVector(env, activeSession, modelInput) }
            if (scores.isEmpty()) {
                Log.w(logTag, "UAS classifier returned no scores")
                return null
            }

            val predictedIndex = scores.indices.maxByOrNull { scores[it] } ?: return null
            val roundedScores = scores.map { roundScore(it) }
            val predictedLabel = labels.getOrElse(predictedIndex) { "class_$predictedIndex" }
            val predictedScore = roundedScores.getOrElse(predictedIndex) { roundScore(scores[predictedIndex]) }

            if (scores.size != labels.size) {
                Log.w(
                    logTag,
                    "UAS classifier output size ${scores.size} does not match label count ${labels.size}"
                )
            }

            AudioClassificationResult(
                predictedIndex = predictedIndex,
                predictedLabel = predictedLabel,
                predictedScore = predictedScore,
                scores = roundedScores,
                labels = labels
            )
        } catch (t: Throwable) {
            Log.e(logTag, "UAS classifier inference failed", t)
            null
        }
    }

    fun close() {
        lock.withLock {
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            session = null
        }
    }

    private fun ensureSession(): OrtSession? =
        lock.withLock {
            session?.let { return@withLock it }
            try {
                val modelBytes = context.assets.open(assetName).use { it.readBytes() }
                val created = env.createSession(modelBytes, OrtSession.SessionOptions())
                session = created
                created
            } catch (t: Throwable) {
                Log.e(logTag, "Failed to load UAS classifier model from assets: $assetName", t)
                null
            }
        }

    private fun roundScore(value: Float): Float = round(value * 10000f) / 10000f
}
