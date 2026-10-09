package com.aitech.weartak_civ.data.takserver

import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A UI request outlives the runtime detail that a configuration refresh may replace. */
class TakServerRegistration {
    enum class Status { SAVING, CONNECTING, CONNECTED, FAILED, DUPLICATE }
    enum class SaveResult { SAVED, DUPLICATE, INVALID }
    data class State(
        val serverId: String? = null,
        val status: Status = Status.FAILED,
        val failure: TakConnectionFailure? = null,
        val requestId: Long = 0,
    ) {
        val isLoading get() = status == Status.SAVING || status == Status.CONNECTING
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var request = 0L
    private var runtime: Any? = null

    @Synchronized
    fun prepare(serverId: String): Long {
        runtime = null
        val ticket = ++request
        mutableState.value = State(serverId, Status.SAVING, requestId = ticket)
        return ticket
    }

    suspend fun saveAndConnect(
        serverId: String,
        save: suspend () -> SaveResult,
        ticket: Long = prepare(serverId),
        connect: suspend () -> Unit,
    ) {
        try {
            when (save()) {
                SaveResult.SAVED -> if (isPending(ticket)) connect()
                SaveResult.DUPLICATE -> finishSave(ticket, Status.DUPLICATE)
                SaveResult.INVALID -> finishSave(ticket, Status.FAILED, unavailable())
            }
        } catch (error: CancellationException) {
            finishSave(ticket, Status.FAILED, unavailable())
            throw error
        } catch (error: Exception) {
            Logger.getLogger("WearTAK-Registration")
                .severe("Registration save/connect failed (${error.javaClass.simpleName})")
            finishSave(ticket, Status.FAILED, unavailable())
        }
    }

    fun failPending(ticket: Long) = finishSave(ticket, Status.FAILED, unavailable())

    @Synchronized
    private fun isPending(ticket: Long) = request == ticket && mutableState.value.isLoading

    @Synchronized
    private fun finishSave(ticket: Long, status: Status, failure: TakConnectionFailure? = null) {
        if (request == ticket && mutableState.value.isLoading) {
            mutableState.value = mutableState.value.copy(status = status, failure = failure)
        }
    }

    @Synchronized
    fun bind(serverId: String, detail: Any) {
        if (mutableState.value.serverId == serverId && mutableState.value.isLoading) {
            runtime = detail
            mutableState.value = mutableState.value.copy(status = Status.CONNECTING, failure = null)
        }
    }

    @Synchronized
    fun replaceRuntime(serverId: String, previous: Any, replacement: Any) {
        if (runtime === previous) bind(serverId, replacement)
    }

    @Synchronized
    fun isPendingRuntime(detail: Any) = runtime === detail && mutableState.value.isLoading

    @Synchronized
    fun publish(serverId: String, detail: Any, status: Status, failure: TakConnectionFailure? = null) {
        if (runtime === detail && mutableState.value.serverId == serverId && mutableState.value.isLoading) {
            mutableState.value = mutableState.value.copy(status = status, failure = failure)
        }
    }

    fun unavailable() = TakConnectionFailure(TakEnrollmentStage.CONNECTION, TakFailureCategory.CONNECTIVITY)
}
