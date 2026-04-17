package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.UasOnnxRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.service.audio.AudioDetectionRuntime
import com.aitech.weartak_civ.service.audio.AudioDetectionSpec
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

class UasOnnxService : BaseAudioDetectionService() {

    private val uasRepo by lazy {
        try {
            (applicationContext as WearTakApplication).uasOnnxRepo
        } catch (_: Throwable) {
            UasOnnxRepository(applicationContext)
        }
    }

    override val logTag: String = TAG
    override val notificationId: Int = NOTIFICATION_ID
    override val runningFlag: AtomicBoolean = isRunning
    override val spec: AudioDetectionSpec = SPEC
    override val feedbackFlow: SharedFlow<AudioDetectionFeedback>
        get() = uasRepo.uasFeedback
    override val runtime: AudioDetectionRuntime by lazy {
        createRuntime { trigger ->
            val event = uasRepo.createUasEvent(
                uid = trigger.uid,
                confidence = trigger.confidence,
                pcmChunk = trigger.pcmChunk,
                triggerPcmPath = trigger.triggerPcmPath,
                audioSource = trigger.audioSource,
                inferStart = trigger.inferStart,
                inferEnd = trigger.inferEnd
            )
            uasRepo.upsertRecentUas(event)
        }
    }

    override fun getEventFromUid(uid: String) = uasRepo.getEventFromUid(uid)

    companion object {
        private const val TAG = "WearTAK-UasOnnxService"
        private const val NOTIFICATION_ID = 920
        val isRunning: AtomicBoolean = AtomicBoolean(false)

        private val SPEC = AudioDetectionSpec(
            detectorKey = "uas",
            assetName = "falcon-bin.onnx",
            threshold = 0.5f,
            debugDirName = "uas_debug",
            logTag = TAG,
            triggerPrefix = "UAS",
            feedbackPrefix = "UAS"
        )
    }
}
