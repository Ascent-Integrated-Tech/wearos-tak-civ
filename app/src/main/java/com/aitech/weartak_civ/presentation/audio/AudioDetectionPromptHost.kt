package com.aitech.weartak_civ.presentation.audio

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.wear.compose.material.MaterialTheme.colors
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun AudioDetectionPromptHost(
    logTag: String,
    logPrefix: String,
    promptText: String,
    events: SharedFlow<AudioDetectionEvent>,
    emitFeedback: (AudioDetectionFeedback) -> Boolean,
    promptTimeoutMs: Long = 15_000L
) {
    val shouldShowDialog = remember { mutableStateOf(false) }
    val pendingEvent = remember { mutableStateOf<AudioDetectionEvent?>(null) }
    val shownAtMs = remember { mutableStateOf(0L) }
    val receivedAtEl = remember { mutableStateOf(0L) }
    val lastDialogUid = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(events) {
        Log.i(logTag, "MainScreen: starting ${logPrefix.lowercase()}Events collector")
        events.collect { event ->
            val recvEl = SystemClock.elapsedRealtime()
            receivedAtEl.value = recvEl

            Log.i(
                logTag,
                "${logPrefix}_UI_RECEIVE uid=${event.uid} conf=${"%.4f".format(event.confidence)} " +
                    "inferEndEl=${event.inferEndElapsedMs} uiRecvEl=$recvEl " +
                    "inferToUiMs=${event.inferEndElapsedMs?.let { recvEl - it }}"
            )

            pendingEvent.value = event
            shownAtMs.value = SystemClock.elapsedRealtime()
            shouldShowDialog.value = true

            Utils.vibrateWatch(vibDurationMs = 150, numberOfVibes = 2, timeBtwnVibesMs = 120)
        }
    }

    fun finishPrompt(eventUid: String, label: AudioDetectionUserLabel) {
        val event = pendingEvent.value
        if (event == null) {
            Log.w(logTag, "${logPrefix}_UI_FINISH uid=$eventUid label=$label ignored (no pending)")
            return
        }
        if (event.uid != eventUid) {
            Log.w(logTag, "${logPrefix}_UI_FINISH uid=$eventUid label=$label ignored (pending=${event.uid})")
            return
        }

        val nowEl = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val latencyMs = nowEl - shownAtMs.value

        Log.i(logTag, "${logPrefix}_UI_FINISH uid=$eventUid label=$label uiLatencyMs=$latencyMs")

        emitFeedback(
            AudioDetectionFeedback(
                eventUid = eventUid,
                label = label,
                respondedAtMs = nowWall,
                responseLatencyMs = nowEl - shownAtMs.value
            )
        )

        shouldShowDialog.value = false
        pendingEvent.value = null
    }

    LaunchedEffect(pendingEvent.value?.uid) {
        val event = pendingEvent.value ?: return@LaunchedEffect
        val uid = event.uid
        delay(promptTimeoutMs)
        val stillSame = shouldShowDialog.value && pendingEvent.value?.uid == uid
        if (stillSame) {
            finishPrompt(uid, AudioDetectionUserLabel.TIMEOUT)
        }
    }

    val event = pendingEvent.value
    if (shouldShowDialog.value && event != null) {
        if (lastDialogUid.value != event.uid) {
            lastDialogUid.value = event.uid
            Log.i(
                logTag,
                "${logPrefix}_UI_DIALOG_SHOW uid=${event.uid} " +
                    "uiRecvToShowMs=${SystemClock.elapsedRealtime() - receivedAtEl.value}"
            )
        }

        DetectionFeedbackDialog(
            promptText = promptText,
            shouldShow = shouldShowDialog.value,
            confidence = event.confidence,
            onDismiss = {
                finishPrompt(event.uid, AudioDetectionUserLabel.UNKNOWN)
                shouldShowDialog.value = false
            },
            onConfirm = {
                finishPrompt(event.uid, AudioDetectionUserLabel.YES)
                shouldShowDialog.value = false
            },
            onReject = {
                finishPrompt(event.uid, AudioDetectionUserLabel.NO)
                shouldShowDialog.value = false
            }
        )
    }
}
