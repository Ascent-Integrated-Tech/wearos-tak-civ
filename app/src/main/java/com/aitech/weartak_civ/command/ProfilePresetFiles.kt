package com.aitech.weartak_civ.command

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException

data class ProfilePresetPreview(
    val fields: Map<String, String>,
    val servers: List<ProfilePresetServerPreview>
)

data class ProfilePresetServerPreview(val name: String, val host: String, val port: Int)

/** View-only projection: credentials and unknown fields never reach the UI. */
object ProfilePresetFiles {
    private const val MAX_BYTES = 64 * 1024
    val profileFields = listOf(
        "callsign", "team", "role", "height", "weight", "pants", "waist", "birthyear", "sex", "usertype"
    )

    fun list(directory: File): List<String> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: throw IOException("Cannot list saved profiles")
        return files.filter { file ->
            file.isFile && file.extension == "json" &&
                ProfilePresetParser.validName(file.nameWithoutExtension) == file.nameWithoutExtension &&
                file.canonicalFile.parentFile == directory.canonicalFile
        }.map { it.nameWithoutExtension }.sorted()
    }

    fun preview(directory: File, name: String): ProfilePresetPreview {
        require(ProfilePresetParser.validName(name) == name) { "Invalid profile name" }
        val file = File(directory, "$name.json")
        if (!file.isFile || file.canonicalFile.parentFile != directory.canonicalFile) {
            throw IOException("Saved profile is unavailable")
        }
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            buffer.copyOf(count)
        }
        if (bytes.size > MAX_BYTES) throw IOException("Saved profile exceeds size limit")
        try {
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val profile = root.get("profile")?.takeUnless { it.isJsonNull }?.asJsonObject
                ?.entrySet()?.associate { it.key.lowercase() to it.value }
            val fields = profileFields.mapNotNull { key ->
                profile?.get(key)?.let { value ->
                    require(value.isJsonPrimitive && !value.asJsonPrimitive.isBoolean)
                    key to value.asString
                }
            }.toMap()
            val servers = root.get("servers")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { raw ->
                val server = raw.asJsonObject
                val serverName = requireNotNull(ChatCommandValidation.serverName(server.get("name")?.asString))
                val host = requireNotNull(ChatCommandValidation.serverAddress(server.get("url")?.asString))
                val port = requireNotNull(ChatCommandValidation.port(server.get("port")?.asString))
                ProfilePresetServerPreview(serverName, host, port)
            }.orEmpty()
            return ProfilePresetPreview(fields, servers)
        } catch (error: JsonParseException) {
            throw IOException("Saved profile is invalid")
        } catch (error: IllegalStateException) {
            throw IOException("Saved profile is invalid")
        } catch (error: IllegalArgumentException) {
            throw IOException("Saved profile is invalid")
        } catch (error: UnsupportedOperationException) {
            throw IOException("Saved profile is invalid")
        }
    }
}
