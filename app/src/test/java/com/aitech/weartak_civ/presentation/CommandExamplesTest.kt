package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.res.Configuration
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.command.ChatCommand
import com.aitech.weartak_civ.command.ChatCommandParse
import com.aitech.weartak_civ.command.ChatCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CommandExamplesTest {
    @Test
    fun integrationDisplayNamesStayConsistentAcrossSupportedLocales() {
        val context = RuntimeEnvironment.getApplication()
        for (language in listOf("en", "ar", "de", "es", "fr", "ja", "ko", "tr", "vi", "zh")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localized = context.createConfigurationContext(configuration)
            assertEquals("Ditto Edge Sync", localized.getString(R.string.ditto))
            assertEquals("Everywhere", localized.getString(R.string.everywhere_comms))
            assertTrue(localized.getString(R.string.ditto_identity).contains("Ditto Edge Sync"))
        }
    }

    @Test
    fun displayedExamplesPreserveQuotesAndParseAsSupportedCommands() {
        val context = RuntimeEnvironment.getApplication()
        val examples = mapOf(
            R.string.chat_command_example_team to ChatCommand("setteam", mapOf("team" to "Dark Green")),
            R.string.chat_command_example_role to ChatCommand("setrole", mapOf("role" to "Team Member")),
            R.string.chat_command_example_callsign to ChatCommand("setcallsign", mapOf("callsign" to "ALPHA-1")),
            R.string.chat_command_example_location_fused to ChatCommand("setlocationinput", mapOf("input" to "enhancedgnss")),
            R.string.chat_command_example_location_gnss to ChatCommand("setlocationinput", mapOf("input" to "gnss")),
        )
        examples.forEach { (resource, command) ->
            val parsed = ChatCommandParser.parse(context.getString(resource))
            assertEquals(ChatCommandParse.Parsed(command), parsed)
        }
    }
}
