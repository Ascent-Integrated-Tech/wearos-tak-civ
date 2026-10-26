package com.aitech.weartak_civ.command

import com.aitech.weartak_civ.cot.CotMessageParser

class PurgeSensitiveEnrollmentCopies

/** Classification is deliberately independent of command validity and execution permission. */
object SensitiveEnrollment {
    private const val SPACE = "[\\s\\p{Z}\\p{javaWhitespace}]*"
    private val textPrefix = Regex("^${SPACE}#weartakcommand${SPACE}enrollserver", RegexOption.IGNORE_CASE)
    private val embeddedPrefix = Regex("#weartakcommand${SPACE}enrollserver", RegexOption.IGNORE_CASE)

    fun isSensitiveText(text: String): Boolean = textPrefix.containsMatchIn(text)

    fun containsSensitiveXml(xml: String): Boolean {
        if (embeddedPrefix.containsMatchIn(xml)) return true
        if (!xml.contains("&#")) return false
        val document = CotMessageParser.parseDocumentOrNull(xml) ?: return false
        return embeddedPrefix.containsMatchIn(document.documentElement.textContent.orEmpty())
    }

    fun containsSensitiveDocument(document: Map<String, Any?>): Boolean =
        (document["msg"] as? String)?.let(::isSensitiveText) == true ||
            (document["r"] as? String)?.let(::containsSensitiveXml) == true

    fun purgeNotifications(context: android.content.Context) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.activeNotifications.forEach { entry ->
            if (containsSensitiveValue(entry.notification.extras)) manager.cancel(entry.tag, entry.id)
        }
    }

    private fun containsSensitiveValue(value: Any?): Boolean = when (value) {
        is CharSequence -> embeddedPrefix.containsMatchIn(value)
        is android.os.Bundle -> value.keySet().any { containsSensitiveValue(value.get(it)) }
        is Array<*> -> value.any(::containsSensitiveValue)
        is Iterable<*> -> value.any(::containsSensitiveValue)
        else -> false
    }
}
