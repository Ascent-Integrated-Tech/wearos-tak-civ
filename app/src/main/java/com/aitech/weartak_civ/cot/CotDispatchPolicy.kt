package com.aitech.weartak_civ.cot

enum class CotMessageKind { PLI, CHAT, MARKER, MARKER_CLEAR, EMERGENCY }

enum class CotRoute { TAK, SITX, DITTO, MULTICAST, COMPANION }

data class CotEndpointAvailability(
    val tak: Boolean,
    val sitx: Boolean,
    val ditto: Boolean,
    val multicast: Boolean,
    val companion: Boolean,
)

object CotDispatchPolicy {
    fun routesFor(kind: CotMessageKind, availability: CotEndpointAvailability): List<CotRoute> =
        when (kind) {
            CotMessageKind.PLI -> buildList {
                if (availability.tak) add(CotRoute.TAK)
                add(CotRoute.SITX)
                if (availability.companion) add(CotRoute.COMPANION)
                add(CotRoute.MULTICAST)
            }
            CotMessageKind.MARKER_CLEAR -> buildList {
                if (availability.companion) add(CotRoute.COMPANION)
                if (availability.ditto) add(CotRoute.DITTO)
                if (availability.tak) add(CotRoute.TAK)
                if (availability.multicast) {
                    add(CotRoute.MULTICAST)
                    add(CotRoute.MULTICAST)
                }
                if (availability.sitx) add(CotRoute.SITX)
            }
            CotMessageKind.CHAT, CotMessageKind.MARKER, CotMessageKind.EMERGENCY -> buildList {
                if (availability.companion) add(CotRoute.COMPANION)
                if (availability.multicast) add(CotRoute.MULTICAST)
                if (availability.ditto) add(CotRoute.DITTO)
                if (availability.tak) add(CotRoute.TAK)
                if (availability.sitx) add(CotRoute.SITX)
            }
        }
}
