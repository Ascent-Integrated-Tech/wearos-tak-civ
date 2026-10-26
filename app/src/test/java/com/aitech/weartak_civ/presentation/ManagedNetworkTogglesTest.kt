package com.aitech.weartak_civ.presentation

import android.app.Application
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ManagedNetworkTogglesTest {
    @Test fun emptyOrUnrelatedBundleHasNoNetworkOverrides() {
        listOf(Bundle(), Bundle().apply { putString("license_key", "unrelated") }).forEach {
            assertEquals(ManagedNetworkToggles(null, null, null, null, null, null, null),
                ManagedNetworkToggles.parse(it))
        }
    }

    @Test fun explicitEmptyEndpointsDisablesAllSelectedEndpoints() {
        val parsed = ManagedNetworkToggles.parse(Bundle().apply {
            putStringArray("select_data_endpoints", emptyArray())
        })
        assertEquals(false, parsed.tak)
        assertEquals(false, parsed.esri)
        assertEquals(false, parsed.tabletCommand)
        assertNull(parsed.multicast)
    }

    @Test fun endpointSelectionIsAuthoritativeIncludingTakExclusion() {
        listOf("TAK", "ESRI", "Tablet Command").forEach { endpoint ->
            val parsed = ManagedNetworkToggles.parse(Bundle().apply {
                putStringArray("select_data_endpoints", arrayOf(endpoint))
            })
            assertEquals(endpoint == "TAK", parsed.tak)
            assertEquals(endpoint == "ESRI", parsed.esri)
            assertEquals(endpoint == "Tablet Command", parsed.tabletCommand)
        }
    }

    @Test fun booleansPreserveAbsentVersusExplicitFalseAndTrue() {
        listOf(false, true).forEach { value ->
            val parsed = ManagedNetworkToggles.parse(Bundle().apply {
                putBoolean("multicast_service_enabled", value)
                putBoolean("sitx_service_enabled", value)
                putBoolean("ditto_service_enabled", value)
                putBoolean("enable_companion_plugin", value)
            })
            assertEquals(listOf(value, value, value, value),
                listOf(parsed.multicast, parsed.sitx, parsed.ditto, parsed.companion))
            assertNull(parsed.tak)
        }
    }

    @Test fun malformedPresentRestrictionsAreRejectedInsteadOfDefaultingOff() {
        listOf("multicast_service_enabled", "sitx_service_enabled",
            "ditto_service_enabled", "enable_companion_plugin", "select_data_endpoints").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                ManagedNetworkToggles.parse(Bundle().apply { putString(key, "false") })
            }
            assertThrows(IllegalArgumentException::class.java) {
                ManagedNetworkToggles.parse(Bundle().apply { putString(key, null) })
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagedNetworkToggles.parse(Bundle().apply {
                putStringArray("select_data_endpoints", arrayOf("unknown"))
            })
        }
    }
}
