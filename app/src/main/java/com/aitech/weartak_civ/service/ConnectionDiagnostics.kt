package com.aitech.weartak_civ.service

import android.net.NetworkCapabilities
import com.aitech.weartak_civ.data.takserver.TakConnectionFailure
import com.aitech.weartak_civ.data.takserver.TakFailureCategory

internal enum class NetworkTransition(val code: Long) {
    SNAPSHOT(0), AVAILABLE(1), CAPABILITIES(2), LOST(3), UNAVAILABLE(4)
}

internal enum class ConnectionTransition(val code: Long) {
    REQUEST(0), START(1), TLS_START(2), SUCCESS(3), FAILURE(4),
    INVALIDATED(5), RETRY_SCHEDULED(6), RETRY_STARTED(7), SKIPPED(8),
    READER_ENDED(9), WRITE_FAILED(10), KEEPALIVE_TIMEOUT(11), STATUS(12)
}

internal enum class ConnectionReason(val code: Long) {
    NONE(0), COMPANION(1), SERVICE_STOPPED(2), TERMINATED(3), DISABLED(4),
    NO_NETWORK(5), DUPLICATE(6), ACTIVE_ATTEMPT(7), ATTEMPT_LIMIT(8),
    STALE_GENERATION(9), AUTHENTICATION(10), HTTP(11), TLS(12),
    CONNECTIVITY(13), CERTIFICATE(14), END_OF_STREAM(15), CANCELLED(16),
    READER_ERROR(17), SOCKET_WRITE(18), KEEPALIVE(19), RESET(20)
}

internal object ConnectionDiagnostics {
    inline fun record(event: DiagnosticEvent, metrics: () -> Map<DiagnosticMetric, Long>) {
        if (AppDiagnostics.recordingEnabled?.value != true) return
        AppDiagnostics.record(event, metrics())
    }

    fun network(transition: NetworkTransition, present: Boolean, capabilities: NetworkCapabilities?) {
        record(DiagnosticEvent.NETWORK_LIFECYCLE) {
            mapOf(
                DiagnosticMetric.KIND to transition.code,
                DiagnosticMetric.ACTIVE to if (present) 1L else 0L,
                DiagnosticMetric.VALIDATED to if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 1L else 0L,
                DiagnosticMetric.INTERNET to if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) 1L else 0L,
                DiagnosticMetric.TRANSPORT to transport(capabilities)
            )
        }
    }

    // A fixed bitmask, not an Android Network handle, SSID, address or subscriber ID.
    private fun transport(capabilities: NetworkCapabilities?): Long {
        if (capabilities == null) return 0
        var mask = 0L
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) mask = mask or 1L
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mask = mask or 2L
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) mask = mask or 4L
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) mask = mask or 8L
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) mask = mask or 16L
        return mask
    }

    fun reason(failure: TakConnectionFailure): ConnectionReason = when (failure.category) {
        TakFailureCategory.AUTHENTICATION -> ConnectionReason.AUTHENTICATION
        TakFailureCategory.HTTP -> ConnectionReason.HTTP
        TakFailureCategory.TLS -> ConnectionReason.TLS
        TakFailureCategory.CONNECTIVITY -> ConnectionReason.CONNECTIVITY
        TakFailureCategory.CERTIFICATE -> ConnectionReason.CERTIFICATE
    }
}
