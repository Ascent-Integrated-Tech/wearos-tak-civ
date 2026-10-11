package com.aitech.weartak_civ.cot

enum class CotMessageKind { PLI, CHAT, MARKER, MARKER_CLEAR, EMERGENCY }

enum class CotRoute { TAK, SITX, DITTO, MULTICAST, COMPANION, PLUGIN }

data class CotEndpointAvailability(
    val tak: Boolean,
    val sitx: Boolean,
    val ditto: Boolean,
    val multicast: Boolean,
    val companion: Boolean,
    /** True when at least one bound plugin has opted in to receiving outbound CoT. */
    val plugin: Boolean = false,
)

object CotDispatchPolicy {
    // PLUGIN is offered first on live kinds. A plugin's transport may be the only one available -
    // a mesh plugin on LoRa still works with no wifi, no cell and no TAK server - so it must not be
    // conditional on any other endpoint being up.

    fun routesFor(kind: CotMessageKind, availability: CotEndpointAvailability): List<CotRoute> =
        // Point removal is a local paired-device operation, never outbound clear CoT.
        if (kind == CotMessageKind.MARKER_CLEAR) emptyList()
        else if (availability.companion) {
            buildList {
                if (availability.plugin) add(CotRoute.PLUGIN)
                add(CotRoute.COMPANION)
            }
        } else when (kind) {
            CotMessageKind.PLI -> buildList {
                if (availability.plugin) add(CotRoute.PLUGIN)
                if (availability.tak) add(CotRoute.TAK)
                add(CotRoute.SITX)
                if (availability.companion) add(CotRoute.COMPANION)
                add(CotRoute.MULTICAST)
            }
            CotMessageKind.MARKER_CLEAR -> emptyList()
            CotMessageKind.CHAT, CotMessageKind.MARKER, CotMessageKind.EMERGENCY -> buildList {
                if (availability.plugin) add(CotRoute.PLUGIN)
                if (availability.companion) add(CotRoute.COMPANION)
                if (availability.multicast) add(CotRoute.MULTICAST)
                if (availability.ditto) add(CotRoute.DITTO)
                if (availability.tak) add(CotRoute.TAK)
                if (availability.sitx) add(CotRoute.SITX)
            }
        }
}
