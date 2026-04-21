/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.GunshotOnnxRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.service.audio.AudioDetectionRuntime
import com.aitech.weartak_civ.service.audio.AudioDetectionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

class GunshotOnnxService : BaseAudioDetectionService() {

    private val gunshotRepo by lazy {
        try {
            (applicationContext as WearTakApplication).gunshotOnnxRepo
        } catch (_: Throwable) {
            GunshotOnnxRepository(applicationContext)
        }
    }
    private val dataRepository by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    override val logTag: String = TAG
    override val notificationId: Int = NOTIFICATION_ID
    override val runningFlag: AtomicBoolean = isRunning
    override val spec: AudioDetectionSpec = SPEC
    override val feedbackFlow: Flow<AudioDetectionFeedback>
        get() = gunshotRepo.gunshotFeedback
    override val thresholdFlow: Flow<Float>
        get() = dataRepository.getOnnxGunshotTriggerThresholdPercent.map { it.coerceIn(0, 100) / 100f }
    override val runtime: AudioDetectionRuntime by lazy {
        createRuntime { trigger ->
            val event = gunshotRepo.createGunshotEvent(
                uid = trigger.uid,
                confidence = trigger.confidence,
                pcmChunk = trigger.pcmChunk,
                triggerPcmPath = trigger.triggerPcmPath,
                audioSource = trigger.audioSource,
                inferStart = trigger.inferStart,
                inferEnd = trigger.inferEnd
            )
            gunshotRepo.upsertRecentGunshot(event)
        }
    }

    override fun getEventFromUid(uid: String): AudioDetectionEvent? = gunshotRepo.getEventFromUid(uid)

    override suspend fun logFeedback(event: AudioDetectionEvent, feedback: AudioDetectionFeedback) {
        Utils.logAudioDetectionFeedback(
            context = applicationContext,
            event = event,
            label = feedback.label,
            respondedAtMs = feedback.respondedAtMs,
            responseLatencyMs = feedback.responseLatencyMs,
            debugDirName = spec.debugDirName,
            extra = feedback.extra
        )
    }

    companion object {
        private const val TAG = "WearTAK-GunshotOnnxService"
        private const val NOTIFICATION_ID = 919
        val isRunning: AtomicBoolean = AtomicBoolean(false)

        private val SPEC = AudioDetectionSpec(
            detectorKey = "gunshot",
            assetName = "dart-bin-2chan_20260327.onnx",
            threshold = DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT / 100f,
            debugDirName = "gunshot_debug",
            logTag = TAG,
            triggerPrefix = "GS",
            feedbackPrefix = "GS"
        )
    }
}
