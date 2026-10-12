package com.aitech.weartak_civ.cot

/** Ordered XML primitives for CoT. Values are raw by design to preserve legacy wire behavior. */
internal object CotXml {
    const val DECLARATION = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"

    fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }

    fun element(
        name: String,
        attributes: List<Pair<String, Any?>> = emptyList(),
        content: String? = null,
    ): String = buildString {
        append('<').append(name)
        attributes.forEach { (key, value) ->
            append(' ').append(key).append("='").append(value).append('\'')
        }
        if (content == null) {
            append("/>")
        } else {
            append('>').append(content).append("</").append(name).append('>')
        }
    }

    fun event(
        attributes: List<Pair<String, Any?>>,
        pointAttributes: List<Pair<String, Any?>>,
        detailChildren: String,
    ): String = DECLARATION + element(
        name = "event",
        attributes = attributes,
        content = element("point", pointAttributes) + element("detail", content = detailChildren),
    )
}
