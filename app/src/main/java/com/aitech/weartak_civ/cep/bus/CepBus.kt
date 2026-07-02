package com.aitech.weartak_civ.cep.bus

import android.util.Log
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.CepPayload
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

object CepBus {

    private const val TAG = "CepBus"

    // Hot stream (producers will emit later from services).
    // Phase 4: buffer raised 256 → 512 to handle native-rate sensor ingestion.
    // Multiple sensors at SENSOR_DELAY_NORMAL (~5 Hz each) can burst ~50+ events/s;
    // 512 gives ~10 s of headroom before DROP_OLDEST kicks in.
    val events = MutableSharedFlow<CepEvent<out CepPayload>>(
        replay = 0,
        extraBufferCapacity = 512,
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


