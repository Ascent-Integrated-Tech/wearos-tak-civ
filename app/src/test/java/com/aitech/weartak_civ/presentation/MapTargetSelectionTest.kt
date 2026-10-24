package com.aitech.weartak_civ.presentation

import org.junit.Assert.*
import org.junit.Test

class MapTargetSelectionTest {
    private fun target(uid: String, lon: Double, kind: MapTargetKind = MapTargetKind.CONTACT) =
        MapSelectionTarget(uid, "Target $uid", kind, 35.12345678901234, lon)

    @Test fun sameCoordinateContactsAndPinsKeepEveryIdentityAndOriginalPrecision() {
        val targets = listOf(target("same", -117.98765432109876),
            target("same", -117.98765432109876, MapTargetKind.DROPPED),
            target("received", -117.98765432109876, MapTargetKind.RECEIVED))
        val groups = groupMapTargets(targets) { _, _ -> MapTargetScreenPoint(100.0, 100.0) }
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().size)
        assertEquals(targets.toSet(), groups.flatten().toSet())
        assertEquals(3, groups.flatten().map { it.key }.toSet().size)
        groups.flatten().forEach {
            assertEquals(35.12345678901234, it.lat, 0.0)
            assertEquals(-117.98765432109876, it.lon, 0.0)
        }
    }

    @Test fun cellBoundaryAndZoomChangesDoNotMakeTargetsUnreachableOrChainDistantTargets() {
        val targets = listOf(target("a", 27.0), target("b", 29.0), target("c", 58.0))
        fun groups(scale: Double) = groupMapTargets(targets) { _, lon ->
            MapTargetScreenPoint(lon * scale, 0.0)
        }
        val close = groups(1.0)
        assertEquals(listOf(2, 1), close.map { it.size })
        assertEquals(3, groups(20.0).size)
        assertEquals(close, groupMapTargets(targets.reversed()) { _, lon -> MapTargetScreenPoint(lon, 0.0) })
        assertEquals(targets.toSet(), close.flatten().toSet())
    }

    @Test fun manySamePositionUpdatesAreTargetsNotDroppedOrExpandedIntoHistory() {
        val current = (0 until 500).map { target("target-$it", -118.0) }
        var projections = 0
        val groups = groupMapTargets(current) { _, _ ->
            projections++
            MapTargetScreenPoint(200.0, 210.0)
        }
        assertEquals(500, projections)
        assertEquals(500, groups.single().size)
        assertEquals(current.map { it.uid }.toSet(), groups.single().map { it.uid }.toSet())
        val next = current.map { it.copy(title = "${it.title} updated") }
        assertEquals(500, groupMapTargets(next) { _, _ -> MapTargetScreenPoint(200.0, 210.0) }.single().size)
    }

    @Test fun unavailableProjectionLeavesGroupingEmptyUntilMapIsReady() {
        assertTrue(groupMapTargets(listOf(target("a", 0.0))) { _, _ -> null }.isEmpty())
    }

    @Test fun equalUidsFromDifferentSourcesRemainSeparatePickerRows() {
        val a = target("same", 10.0, MapTargetKind.RECEIVED).copy(sourceServerId = "server-a")
        val b = a.copy(sourceServerId = "server-b")
        assertNotEquals(a.key, b.key)
        val group = groupMapTargets(listOf(a, b)) { _, _ -> MapTargetScreenPoint(100.0, 100.0) }.single()
        assertEquals(setOf(a, b), group.toSet())
        assertEquals(2, group.map { it.key }.toSet().size)
    }
}
