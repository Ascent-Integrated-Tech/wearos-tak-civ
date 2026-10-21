package com.aitech.weartak_civ.cep.metric

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Generation-directory persistence boundary for V2 model state.
 *
 * The model layer supplies serialized history and state. This class owns only
 * the commit protocol: a generation is complete before CURRENT is replaced.
 */
class BaselineV2GenerationStore(private val rootDir: File) {
    private val lock = Any()

    init {
        rootDir.mkdirs()
    }

    fun commit(payload: BaselineV2GenerationPayload) = synchronized(lock) {
        require(payload.generation >= 0L) { "Generation must not be negative" }
        val staging = File(rootDir, "generation-${payload.generation}.tmp")
        val committed = File(rootDir, "generation-${payload.generation}")
        deleteRecursively(staging)
        staging.mkdirs()
        writeSynced(File(staging, HISTORY_FILE), payload.history)
        writeSynced(File(staging, STATE_FILE), payload.state)
        writeManifest(File(staging, MANIFEST_FILE), payload)
        deleteRecursively(committed)
        moveDirectory(staging, committed)
        val currentTemp = File(rootDir, "$CURRENT_FILE.tmp")
        writeSynced(currentTemp, payload.generation.toString().toByteArray())
        moveFile(currentTemp, File(rootDir, CURRENT_FILE))
    }

    fun loadCommitted(): BaselineV2GenerationPayload? = synchronized(lock) {
        val current = File(rootDir, CURRENT_FILE)
        val generation = current.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return null
        val dir = File(rootDir, "generation-$generation")
        val manifest = File(dir, MANIFEST_FILE)
        val history = File(dir, HISTORY_FILE)
        val state = File(dir, STATE_FILE)
        if (!dir.isDirectory || !manifest.isFile || !history.isFile || !state.isFile) return null
        val values = manifest.readLines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        if (values["generation"]?.toLongOrNull() != generation) return null
        if (values["history_sha256"] != sha256(history.readBytes())) return null
        if (values["state_sha256"] != sha256(state.readBytes())) return null
        return BaselineV2GenerationPayload(generation, history.readBytes(), state.readBytes())
    }

    fun clear() = synchronized(lock) {
        rootDir.listFiles()?.forEach { deleteRecursively(it) }
    }

    private fun writeManifest(file: File, payload: BaselineV2GenerationPayload) {
        val content = buildString {
            appendLine("generation=${payload.generation}")
            appendLine("history_sha256=${sha256(payload.history)}")
            appendLine("state_sha256=${sha256(payload.state)}")
        }
        writeSynced(file, content.toByteArray())
    }

    private fun moveDirectory(source: File, target: File) = moveFile(source, target)

    private fun moveFile(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        check(file.delete()) { "Unable to delete ${file.absolutePath}" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val CURRENT_FILE = "CURRENT"
        const val HISTORY_FILE = "history.bin"
        const val STATE_FILE = "state.bin"
        const val MANIFEST_FILE = "manifest"
    }
}

data class BaselineV2GenerationPayload(
    val generation: Long,
    val history: ByteArray,
    val state: ByteArray
)
