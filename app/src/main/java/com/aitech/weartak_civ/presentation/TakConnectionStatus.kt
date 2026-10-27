package com.aitech.weartak_civ.presentation

import androidx.annotation.StringRes
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.service.TAKConnectService

@StringRes
internal fun takServerStatusLabel(enabled: Boolean, status: String, offBodyWaiting: Boolean): Int =
    when {
        !enabled -> R.string.tak_status_off
        status == TAKConnectService.TAK_SERVER_CONNECTION_FAILED_STRING -> R.string.connection_failed
        status == TAKConnectService.TAK_SERVER_CONNECTED_STRING -> R.string.tak_status_connected
        status == TAKConnectService.TAK_SERVER_CONNECTING_STRING -> R.string.server_connecting
        offBodyWaiting && status == TAKConnectService.TAK_SERVER_DISCONNECTED_STRING ->
            R.string.physio_not_worn
        else -> R.string.tak_status_not_connected
    }

@StringRes
internal fun takNetworkStatusOverride(
    masterEnabled: Boolean,
    enabledServerStatuses: List<String>,
    offBodyWaiting: Boolean,
): Int? = when {
    !masterEnabled || enabledServerStatuses.isEmpty() -> null
    TAKConnectService.TAK_SERVER_CONNECTION_FAILED_STRING in enabledServerStatuses ->
        R.string.connection_failed
    offBodyWaiting && enabledServerStatuses.all {
        it == TAKConnectService.TAK_SERVER_DISCONNECTED_STRING
    } -> R.string.physio_not_worn
    else -> null
}
