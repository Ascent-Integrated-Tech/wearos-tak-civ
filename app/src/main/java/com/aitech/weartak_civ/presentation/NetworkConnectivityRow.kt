package com.aitech.weartak_civ.presentation

import androidx.annotation.StringRes
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DittoPdlSource
import java.text.Collator
import java.util.Locale

internal data class NetworkConnectivityRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val checked: Boolean,
    val toggle: (Boolean) -> Unit,
    val statusDescription: String = subtitle
)

internal fun List<NetworkConnectivityRow>.alphabetizedConnections(locale: Locale): List<NetworkConnectivityRow> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return sortedWith { a, b ->
        collator.compare(a.title, b.title).takeIf { it != 0 } ?: a.key.compareTo(b.key)
    }
}

@StringRes
internal fun dittoPdlSubtitle(source: DittoPdlSource, hasCustomPdl: Boolean): Int = when (source) {
    DittoPdlSource.COMMUNITY -> R.string.ditto_community_subtitle
    DittoPdlSource.CUSTOM -> if (hasCustomPdl) R.string.ditto_custom_subtitle else R.string.ditto_no_pdl_subtitle
}
