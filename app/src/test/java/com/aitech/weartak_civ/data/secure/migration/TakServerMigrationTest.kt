/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.secure.migration

import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.data.takserver.TakServerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TakServerMigrationTest {
    @Test
    fun acceptsOnlyCanonicalUuidValues() {
        assertEquals(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            TakServerId.normalizeOrNull("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
        )
        assertNull(TakServerId.normalizeOrNull("1-1-1-1-1"))
    }

    @Test
    fun assignsMissingIdsWithoutChangingServerSettingsOrCredentials() {
        val original = server(serverId = null)

        val migrated = TakServerMigration.migrateItems(listOf(original)).single()

        assertNotNull(TakServerId.normalizeOrNull(migrated.serverId))
        assertEquals(original.copy(serverId = migrated.serverId), migrated)
        assertEquals("user", migrated.username)
        assertEquals("password", migrated.password)
        assertEquals("certificate", migrated.p12Cert)
    }

    @Test
    fun preservesAnExistingIdAndAssignsDifferentIdsToDifferentServers() {
        val existingId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val migrated = TakServerMigration.migrateItems(
            listOf(
                server(address = "one.example", serverId = existingId),
                server(address = "two.example", serverId = null)
            )
        )

        assertEquals(existingId, migrated[0].serverId)
        assertNotNull(TakServerId.normalizeOrNull(migrated[1].serverId))
        assertNotEquals(migrated[0].serverId, migrated[1].serverId)
    }

    @Test
    fun replacesDuplicateIdsWithoutChangingEitherServerRecord() {
        val duplicateId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val migrated = TakServerMigration.migrateItems(
            listOf(
                server(address = "one.example", serverId = duplicateId),
                server(address = "two.example", serverId = duplicateId)
            )
        )

        assertEquals(duplicateId, migrated[0].serverId)
        assertNotEquals(migrated[0].serverId, migrated[1].serverId)
        assertEquals("two.example", migrated[1].address)
    }

    private fun server(
        address: String = "tak.example",
        serverId: String?
    ) = TakServerItem(
        isEnabled = true,
        name = "Server",
        address = address,
        port = 8089,
        isP12Cert = true,
        username = "user",
        password = "password",
        p12Cert = "certificate",
        p12CertPassword = "cert-password",
        serverId = serverId
    )
}
