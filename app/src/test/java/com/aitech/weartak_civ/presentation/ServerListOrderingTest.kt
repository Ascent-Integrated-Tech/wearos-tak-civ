package com.aitech.weartak_civ.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerListOrderingTest {
    @Test
    fun alphabetizesNamesIgnoringCaseAndPreservesSourceIndices() {
        val servers = listOf("WearTAK Internal", "nctak", "AFTAK CoE", "Bravo")
        val entries = servers.alphabetizedServerEntries { it }

        assertEquals(listOf("AFTAK CoE", "Bravo", "nctak", "WearTAK Internal"), entries.map { it.value })
        assertEquals(listOf(2, 3, 1, 0), entries.map { it.index })
        assertEquals(listOf("WearTAK Internal", "nctak", "AFTAK CoE", "Bravo"), servers)
    }

    @Test
    fun equalNamesKeepTheirOriginalOrder() {
        val entries = listOf("alpha", "ALPHA", "Alpha").alphabetizedServerEntries { it }
        assertEquals(listOf(0, 1, 2), entries.map { it.index })
    }

    @Test
    fun handlesEmptyAndSingleServerLists() {
        assertEquals(emptyList<IndexedValue<String>>(), emptyList<String>().alphabetizedServerEntries { it })
        assertEquals(listOf(IndexedValue(0, "NCTAK")), listOf("NCTAK").alphabetizedServerEntries { it })
    }
}
