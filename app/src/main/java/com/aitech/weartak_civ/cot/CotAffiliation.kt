package com.aitech.weartak_civ.cot

enum class CotAffiliation(val label: String, val argb: Long) {
    FRIENDLY("Friendly", 0xFF9FFFFFL),
    HOSTILE("Hostile", 0xFFFF8080L),
    NEUTRAL("Neutral", 0xFF80FF80L),
    UNKNOWN("Unknown", 0xFFFFCD57L);

    companion object {
        private val atomType = Regex("a-[a-z](-[A-Za-z0-9]+)+")

        fun isAtomType(type: String): Boolean = atomType.matches(type)

        fun fromType(type: String): CotAffiliation {
            if (!isAtomType(type)) return entries.firstOrNull { it.label == type } ?: UNKNOWN
            return when (type[2]) {
                'f', 'a' -> FRIENDLY
                'h', 's', 'j', 'k' -> HOSTILE
                'n' -> NEUTRAL
                else -> UNKNOWN
            }
        }
    }
}
