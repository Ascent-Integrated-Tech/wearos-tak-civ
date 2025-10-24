package com.aitech.weartak_civ.cep.bus

import android.util.Log
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.CepPayload
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

object CepBus {

    private const val TAG = "CepBus"

    // Hot stream (producers will emit later from services)
    val events = MutableSharedFlow<CepEvent<out CepPayload>>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    suspend fun emit(event: CepEvent<out CepPayload>) {

        /*try {
            Log.d(
                TAG,
                "Emit → topic=${event.topic}, ts=${event.ts}, payload=${event.payload}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Emit log failed: ${t.message}")
        }*/

        events.emit(event)
    }

    fun tryEmit(event: CepEvent<out CepPayload>): Boolean {
        val result = events.tryEmit(event)
        /*try {
            Log.d(
                TAG,
                "TryEmit → topic=${event.topic}, ts=${event.ts}, payload=${event.payload}, success=$result"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "TryEmit log failed: ${t.message}")
        }*/
        return result
    }
}


