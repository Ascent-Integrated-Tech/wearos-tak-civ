/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TakServerManagedReconcilerTest {
    @Test
    fun matchesEndpointEditsByUniqueNameAndRenamesByEndpoint() {
        val existing = listOf(
            server("Alpha", "old-alpha.example", SERVER_A),
            server("Bravo", "bravo.example", SERVER_B)
        )
        val configured = listOf(
            server("Alpha", "new-alpha.example", null),
            server("Renamed Bravo", "bravo.example", null)
        )

        assertEquals(
            listOf(0, 1),
            TakServerManagedReconciler.matchExistingIndexes(configured, existing)
        )
    }

    @Test
    fun preservesIdentityAcrossReorderingAndSimultaneousFieldEdits() {
        val existing = listOf(
            server("Alpha", "alpha.example", SERVER_A),
            server("Bravo", "bravo.example", SERVER_B)
        )
        val reordered = listOf(
            server("Bravo", "bravo.example", null),
            server("Alpha", "alpha.example", null)
        )
        val fullyEditedInPlace = listOf(
            server("New Alpha", "new-alpha.example", null),
            server("New Bravo", "new-bravo.example", null)
        )

        assertEquals(
            listOf(1, 0),
            TakServerManagedReconciler.matchExistingIndexes(reordered, existing)
        )
        assertEquals(
            listOf(0, 1),
            TakServerManagedReconciler.matchExistingIndexes(fullyEditedInPlace, existing)
        )
    }

    @Test
    fun pairsTheRemainingServerAfterAnotherServerMoves() {
        val existing = listOf(
            server("Alpha", "alpha.example", SERVER_A),
            server("Bravo", "bravo.example", SERVER_B)
        )
        val configured = listOf(
            server("Bravo", "bravo.example", null),
            server("New Alpha", "new-alpha.example", null)
        )

        assertEquals(
            listOf(1, 0),
            TakServerManagedReconciler.matchExistingIndexes(configured, existing)
        )
    }

    @Test
    fun explicitIdsWinWhenNamesEndpointsAndOrderChange() {
        val existing = listOf(
            server("Alpha", "alpha.example", SERVER_A),
            server("Bravo", "bravo.example", SERVER_B)
        )
        val configured = listOf(
            server("Renamed Bravo", "new-bravo.example", SERVER_B),
            server("Renamed Alpha", "new-alpha.example", SERVER_A)
        )

        assertEquals(
            listOf(1, 0),
            TakServerManagedReconciler.matchExistingIndexes(configured, existing)
        )
    }

    private fun server(name: String, address: String, serverId: String?) = TakServerItem(
        isEnabled = true,
        name = name,
        address = address,
        port = 8089,
        isP12Cert = false,
        username = "user",
        password = "password",
        p12Cert = "",
        p12CertPassword = "",
        serverId = serverId
    )

    private companion object {
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
