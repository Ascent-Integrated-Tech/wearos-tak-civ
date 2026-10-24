package com.aitech.weartak_civ.data

import androidx.annotation.StringRes
import com.aitech.weartak_civ.R

enum class MoppStatus(val key: String, @StringRes val label: Int) {
    NONE("none", R.string.mopp_none),
    LEVEL_0("0", R.string.mopp_0),
    LEVEL_1("1", R.string.mopp_1),
    LEVEL_2("2", R.string.mopp_2),
    LEVEL_3("3", R.string.mopp_3),
    LEVEL_4("4", R.string.mopp_4);

    companion object {
        fun fromKey(key: String?): MoppStatus = entries.firstOrNull { it.key == key } ?: NONE
    }
}
