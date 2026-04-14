package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.OnnxRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.service.audio.AudioDetectionRuntime
import com.aitech.weartak_civ.service.audio.AudioDetectionSpec
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

class OnnxService : BaseAudioDetectionService() {

    private val onnxRepo by lazy {
        try {
            (applicationContext as WearTakApplication).onnxRepo
        } catch (_: Throwable) {
            OnnxRepository(applicationContext)
        }
    }

    override val logTag: String = TAG
    override val notificationId: Int = NOTIFICATION_ID
    override val runningFlag: AtomicBoolean = isRunning
    override val spec: AudioDetectionSpec = SPEC
    override val feedbackFlow: SharedFlow<AudioDetectionFeedback>
        get() = onnxRepo.gunshotFeedback
    override val runtime: AudioDetectionRuntime by lazy {
        createRuntime { trigger ->
            val event = onnxRepo.createOnnxGunshotEvent(
                uid = trigger.uid,
                confidence = trigger.confidence,
                pcmChunk = trigger.pcmChunk,
                triggerPcmPath = trigger.triggerPcmPath,
                audioSource = trigger.audioSource,
                inferStart = trigger.inferStart,
                inferEnd = trigger.inferEnd
            )
            onnxRepo.upsertRecentGunshot(event)
        }
    }

    override fun getEventFromUid(uid: String) = onnxRepo.getEventFromUid(uid)

    companion object {
        private const val TAG = "WearTAK-OnnxService"
        private const val NOTIFICATION_ID = 919
        val isRunning: AtomicBoolean = AtomicBoolean(false)

        private val SPEC = AudioDetectionSpec(
            assetName = "dart_bin_20260225.onnx",
            threshold = 0.5f,
            debugDirName = "gunshot_debug",
            logTag = TAG,
            triggerPrefix = "GS",
            feedbackPrefix = "GS"
        )
    }
}
