package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cot.CotAffiliation
import org.junit.Assert.*
import org.junit.Test

class MarkerVisualSpecTest {
    private val affiliations = mapOf(
        'f' to CotAffiliation.FRIENDLY, 'a' to CotAffiliation.FRIENDLY,
        'h' to CotAffiliation.HOSTILE, 's' to CotAffiliation.HOSTILE,
        'j' to CotAffiliation.HOSTILE, 'k' to CotAffiliation.HOSTILE,
        'n' to CotAffiliation.NEUTRAL, 'u' to CotAffiliation.UNKNOWN,
        'p' to CotAffiliation.UNKNOWN, 'o' to CotAffiliation.UNKNOWN
    )

    @Test fun genericTypesUseIosAffiliationMappingForIconsLabelsAndClearedFrames() {
        affiliations.forEach { (code, affiliation) ->
            val type = "a-$code-G-U-C"
            assertEquals(affiliation, CotAffiliation.fromType(type))
            assertEquals(markerVisualSpec(affiliation.label).icon, markerVisualSpec(type).icon)
            assertEquals(markerVisualSpec(affiliation.label, cleared = true).icon,
                markerVisualSpec(type, cleared = true).icon)
            assertEquals(affiliation.label, markerTextFromString(type))
        }
        assertEquals(R.drawable.unknown, markerVisualSpec("a-z-G-X").icon)
        listOf("", "garbage", "a-", "a-f", "a-f-", "a--G", "b-t-f").forEach {
            assertEquals("Unknown", markerTextFromString(it))
        }
    }

    @Test fun platformSymbolsAreAffiliationIndependentAndTinted() {
        val symbols = mapOf(
            "A-C-F" to (R.drawable.ic_track_fixed to R.drawable.ic_track_fixed_no_gps),
            "A-M-F" to (R.drawable.ic_track_fixed to R.drawable.ic_track_fixed_no_gps),
            "A-C-H" to (R.drawable.ic_track_rotary to R.drawable.ic_track_rotary_no_gps),
            "A-M-H-X" to (R.drawable.ic_track_rotary to R.drawable.ic_track_rotary_no_gps),
            "S-X" to (R.drawable.ic_track_boat to R.drawable.ic_track_boat_no_gps),
            "G-U-C-I-I" to (R.drawable.ic_track_veh to R.drawable.ic_track_veh_no_gps),
            "G-E-V-C" to (R.drawable.ic_track_veh to R.drawable.ic_track_veh_no_gps)
        )
        affiliations.forEach { (code, affiliation) ->
            symbols.forEach { (suffix, icons) ->
                val type = "a-$code-$suffix"
                val active = markerVisualSpec(type)
                assertEquals(type, icons.first, active.icon)
                assertEquals(type, affiliation.argb, active.tintArgb)
                assertTrue(type, active.label.startsWith("${affiliation.label} "))
                val cleared = markerVisualSpec(type, cleared = true)
                assertEquals(type, icons.second, cleared.icon)
                assertEquals(0xFF808080L, cleared.tintArgb)
            }
        }
        assertEquals(R.drawable.neutral, markerVisualSpec("a-n-A-C-FOO").icon)
    }

    @Test fun uavAndSpiPrecedeGenericAircraftAndAffiliationFallbacks() {
        val uavs = mapOf('f' to R.drawable.drone_icon_friendly, 'a' to R.drawable.drone_icon_friendly,
            'h' to R.drawable.drone_icon_hostile, 's' to R.drawable.drone_icon_hostile,
            'j' to R.drawable.drone_icon_hostile, 'k' to R.drawable.drone_icon_hostile,
            'u' to R.drawable.drone_icon, 'p' to R.drawable.drone_icon,
            'o' to R.drawable.drone_icon, 'n' to R.drawable.drone_icon)
        uavs.forEach { (code, icon) ->
            val type = "a-$code-A-M-F-Q"
            assertEquals(icon, markerVisualSpec(type).icon)
            assertEquals("${affiliations.getValue(code).label} UAV", markerTextFromString(type))
            assertEquals(R.drawable.ic_track_uas_no_gps, markerVisualSpec(type, cleared = true).icon)
        }
        assertEquals(CotAffiliation.NEUTRAL.argb, markerVisualSpec("a-n-A-M-F-Q").tintArgb)
        assertEquals(R.drawable.spi1_icon, markerVisualSpec("b-m-p-s-p-i").icon)
        assertEquals(R.drawable.spi1_icon, markerVisualSpec("b-m-p-s-p-i", cleared = true).icon)
        assertEquals("SPI", markerTextFromString("b-m-p-s-p-i"))
    }

    @Test fun atosSpecificSymbolsAndGpsOffVariantsArePreservedForEveryAffiliation() {
        val symbols = mapOf(
            "G" to R.drawable.ic_track_pax, "G-U-C-I" to R.drawable.ic_track_pax,
            "G-A" to R.drawable.ic_track_hound, "G-U-C-I-I" to R.drawable.ic_track_veh,
            "G-E" to R.drawable.ic_track_equipment, "G-U-S-M" to R.drawable.ic_track_medical,
            "G-E-W" to R.drawable.ic_track_weapon, "G-c" to R.drawable.ic_track_custom,
            "A" to R.drawable.ic_track_bundle, "S" to R.drawable.ic_track_boat,
            "A-C-F" to R.drawable.ic_track_fixed, "A-C-H" to R.drawable.ic_track_rotary,
            "A-M" to R.drawable.ic_track_uas
        )
        affiliations.forEach { (code, _) ->
            symbols.forEach { (suffix, icon) ->
                val type = "a-$code-$suffix"
                assertEquals(type, icon, markerVisualSpec(type, atos = true).icon)
                assertNotEquals(type, icon, markerVisualSpec(type, cleared = true, atos = true).icon)
            }
        }
        assertEquals(R.drawable.ic_track_equipment, markerVisualSpec("a-g-G-E", atos = true).icon)
    }
}
