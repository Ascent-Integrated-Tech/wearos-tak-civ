package com.aitech.weartak_civ.data

import android.location.LocationManager
import androidx.annotation.StringRes
import com.aitech.weartak_civ.R

enum class LocationInput(val provider: String, @StringRes val label: Int) {
    FUSED("fused", R.string.location_input_fused),
    INTERNAL_GNSS(LocationManager.GPS_PROVIDER, R.string.location_input_gnss),
    NETWORK(LocationManager.NETWORK_PROVIDER, R.string.location_input_network);

    companion object {
        fun fromKey(key: String?): LocationInput? =
            if (key == null) FUSED else entries.firstOrNull { it.provider == key }

        val selectable = listOf(FUSED, INTERNAL_GNSS)
    }
}

enum class LocationInputStatus {
    OFF, ACQUIRING, READY, UNAVAILABLE, DISABLED, PERMISSION_REQUIRED, REQUEST_FAILED
}
