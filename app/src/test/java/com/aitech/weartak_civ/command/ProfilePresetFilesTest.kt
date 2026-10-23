package com.aitech.weartak_civ.command

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfilePresetFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun listsOnlyLoadableFilesInStableOrderAndDoesNotCreateDirectories() {
        val missing = File(folder.root, "missing")
        assertTrue(ProfilePresetFiles.list(missing).isEmpty())
        assertFalse(missing.exists())
        folder.newFile("zulu.json").writeText("{}")
        folder.newFile("alpha.json").writeText("{}")
        folder.newFile("Upper.json")
        folder.newFile("not a name.json")
        folder.newFile("ignore.txt")
        folder.newFolder("nested.json")
        assertEquals(listOf("alpha", "zulu"), ProfilePresetFiles.list(folder.root))
    }

    @Test fun projectsOnlyKnownProfileFieldsAndServerIdentityNeverCredentials() {
        val file = folder.newFile("odin.json")
        file.writeText("""{"profile":{"CALLSIGN":"ODIN","pants":30,"waist":"32","password":"SECRET",
            "unknown":"PRIVATE"},"servers":[{"name":"TAK","url":"tak.example","port":8089,
            "username":"USER","password":"SECRET","token":"TOKEN"}],"password":"SECRET"}""")
        val before = file.readBytes()
        val preview = ProfilePresetFiles.preview(folder.root, "odin")
        assertEquals(mapOf("callsign" to "ODIN", "pants" to "30", "waist" to "32"), preview.fields)
        assertEquals(listOf(ProfilePresetServerPreview("TAK", "tak.example", 8089)), preview.servers)
        assertFalse(preview.toString().contains("SECRET"))
        assertFalse(preview.toString().contains("USER"))
        assertFalse(preview.toString().contains("TOKEN"))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun missingSectionsAreEmptyAndServerPortUsesLoadProfileDefault() {
        folder.newFile("empty.json").writeText("{}")
        assertEquals(ProfilePresetPreview(emptyMap(), emptyList()),
            ProfilePresetFiles.preview(folder.root, "empty"))
        folder.newFile("server.json").writeText("""{"servers":[{"name":"TAK","url":"tak.example"}]}""")
        assertEquals(8089, ProfilePresetFiles.preview(folder.root, "server").servers.single().port)
    }

    @Test fun acceptsExactlyTheSame64KiBSizeLimitAsLoadProfile() {
        val file = folder.newFile("limit.json")
        file.writeText("{}" + " ".repeat(64 * 1024 - 2))
        assertEquals(ProfilePresetPreview(emptyMap(), emptyList()),
            ProfilePresetFiles.preview(folder.root, "limit"))
        file.appendText(" ")
        assertThrows(IOException::class.java) { ProfilePresetFiles.preview(folder.root, "limit") }
    }

    @Test fun rejectsMalformedOversizedAndCredentialBearingHostsWithoutLeakingInput() {
        val invalid = listOf(
            """{"password":"SECRET","profile":""",
            """{"profile":{"callsign":{"password":"SECRET"}}}""",
            """{"servers":[{"name":"TAK","url":"user:SECRET@tak.example"}]}""",
            """{"servers":[{"name":"TAK","url":"tak.example","port":0}]}""",
            "x".repeat(64 * 1024 + 1)
        )
        val file = folder.newFile("bad.json")
        for (json in invalid) {
            file.writeText(json)
            val error = assertThrows(IOException::class.java) { ProfilePresetFiles.preview(folder.root, "bad") }
            assertFalse(error.toString().contains("SECRET"))
            assertNull(error.cause)
        }
        assertThrows(IOException::class.java) { ProfilePresetFiles.preview(folder.root, "missing") }
        assertThrows(IllegalArgumentException::class.java) { ProfilePresetFiles.preview(folder.root, "../bad") }
    }
}
