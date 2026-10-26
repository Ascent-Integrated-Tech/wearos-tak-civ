package com.aitech.weartak_civ.data.takserver

import java.io.IOException
import okhttp3.Call
import okhttp3.Response
import android.net.Network
import okhttp3.OkHttpClient

/** Cancels only HTTP work belonging to one TAK connection generation. */
class TakHttpSession(val network: Network? = null, private val allowed: () -> Boolean) {
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private val pool = okhttp3.ConnectionPool()
    private val dns = network?.let { route ->
        object : okhttp3.Dns {
            override fun lookup(hostname: String) = route.getAllByName(hostname).toList()
        }
    }

    fun route(client: OkHttpClient): OkHttpClient {
        ensureCurrent()
        val route = network ?: return client
        return client.newBuilder()
            .socketFactory(route.socketFactory)
            .dns(requireNotNull(dns))
            .connectionPool(pool)
            .build()
    }

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
        pool.evictAll()
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
