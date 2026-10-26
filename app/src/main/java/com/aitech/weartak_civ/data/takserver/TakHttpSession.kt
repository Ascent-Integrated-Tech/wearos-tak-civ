package com.aitech.weartak_civ.data.takserver

import java.io.IOException
import okhttp3.Call
import okhttp3.Response

/** Cancels only HTTP work belonging to one TAK connection generation. */
class TakHttpSession(private val allowed: () -> Boolean) {
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()

    fun register(call: Call) {
        val permitted = allowed()
        synchronized(lock) {
            if (closed || !permitted) {
                call.cancel()
                throw IOException("TAK connection session is no longer available")
            }
            calls.add(call)
        }
    }

    fun unregister(call: Call) = synchronized(lock) { calls.remove(call); Unit }

    fun ensureCurrent() {
        val permitted = allowed()
        synchronized(lock) {
            if (closed || !permitted) throw IOException("TAK connection session is no longer available")
        }
    }

    fun cancel() {
        val pending = synchronized(lock) {
            closed = true
            calls.toList()
        }
        pending.forEach(Call::cancel)
    }
}

inline fun <T> Call.withSessionResponse(session: TakHttpSession?, block: (Response) -> T): T {
    session?.register(this)
    try {
        val result = execute().use(block)
        session?.ensureCurrent()
        return result
    } finally {
        session?.unregister(this)
    }
}
