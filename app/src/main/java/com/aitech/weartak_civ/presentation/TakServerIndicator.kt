package com.aitech.weartak_civ.presentation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.aitech.weartak_civ.R

internal enum class TakServerIndicator(
    @DrawableRes val drawable: Int,
    @StringRes val label: Int
) {
    NO_SERVER(R.drawable.tak_server_disconnected, R.string.tak_status_no_server),
    OFF(R.drawable.tak_server_connecting, R.string.tak_status_off),
    NOT_CONNECTED(R.drawable.tak_server_connecting, R.string.tak_status_not_connected),
    CONNECTED(R.drawable.tak_server_connected, R.string.tak_status_connected);

    companion object {
        fun resolve(hasServer: Boolean, masterEnabled: Boolean, anyConnected: Boolean) = when {
            !hasServer -> NO_SERVER
            !masterEnabled -> OFF
            anyConnected -> CONNECTED
            else -> NOT_CONNECTED
        }
    }
}
