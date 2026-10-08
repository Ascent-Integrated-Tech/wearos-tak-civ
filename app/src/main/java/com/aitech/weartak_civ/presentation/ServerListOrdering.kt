package com.aitech.weartak_civ.presentation

internal fun <T> List<T>.alphabetizedServerEntries(
    name: (T) -> String
): List<IndexedValue<T>> = withIndex().sortedWith(
    compareBy(String.CASE_INSENSITIVE_ORDER) { name(it.value) }
)
