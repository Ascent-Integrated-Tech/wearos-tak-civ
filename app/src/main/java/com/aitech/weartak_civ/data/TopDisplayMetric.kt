package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.R

enum class TopDisplayMetric(val key: String, val label: Int, val icon: Int, val physiological: Boolean) {
    EXERTION("key_exertion", R.string.exertion, R.drawable.exertion, true),
    HEART_RATE("key_heart_rate", R.string.heart_rate, R.drawable.heart, true),
    LAT_LONG("key_lat_long", R.string.metric_lat_long, R.drawable.location_on, false),
    MGRS("key_mgrs", R.string.metric_mgrs, R.drawable.location_on, false),
    SKIN_TEMP("key_skin_temp", R.string.skin_temp, R.drawable.skin_temp, true),
    STEPS("key_steps", R.string.steps, R.drawable.steps, true);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: EXERTION
    }
}
