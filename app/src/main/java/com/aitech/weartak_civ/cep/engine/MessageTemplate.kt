package com.aitech.weartak_civ.cep.engine

internal object MessageTemplate {
    private val PLACEHOLDER_REGEX = "\\{([a-zA-Z0-9._-]+)\\}".toRegex()
    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val SPACE_BEFORE_PUNCT_REGEX = "\\s+([,.:;!?])".toRegex()

    fun extractKeys(template: String): List<String> {
        if (!template.contains('{')) return emptyList()
        val keys = LinkedHashSet<String>()
        PLACEHOLDER_REGEX.findAll(template).forEach { match ->
            keys += match.groupValues[1]
        }
        return keys.toList()
    }

    fun expand(template: String, snapshot: Map<String, Any?>): String {
        if (!template.contains('{')) return template
        return PLACEHOLDER_REGEX.replace(template) { match ->
            snapshot[match.groupValues[1]]?.toString() ?: "?"
        }
    }

    fun strip(template: String): String {
        if (!template.contains('{')) return template
        return SPACE_BEFORE_PUNCT_REGEX
            .replace(WHITESPACE_REGEX.replace(PLACEHOLDER_REGEX.replace(template, ""), " "), "$1")
            .trim()
    }
}
