package net.aabergs.services

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class TemporaryFileInfo(val tempFileId: String, val filePath: Path, val expiresAt: Long)

class TemporaryFileStorage(storageDirectory: String) {
    private val tempRoot: Path = Path.of(storageDirectory).toAbsolutePath().normalize().resolve(".tmp")

    init {
        Files.createDirectories(tempRoot)
    }

    fun storeTemporaryFromStream(input: InputStream, maxUploadBytes: Long, ttlSeconds: Long): TemporaryFileInfo {
        val tempId = UUID.randomUUID().toString()
        val filePath = filePath(tempId)
        val expiresAt = System.currentTimeMillis() + ttlSeconds * 1000

        try {
            StreamFileWriter.writeStreamAtomically(tempRoot, filePath, input, maxUploadBytes)
            writeExpiresAt(tempId, expiresAt)
            return TemporaryFileInfo(tempId, filePath, expiresAt)
        } catch (e: Exception) {
            Files.deleteIfExists(filePath)
            Files.deleteIfExists(metaPath(tempId))
            throw e
        }
    }

    fun getTemporaryFileInfo(tempFileId: String): TemporaryFileInfo? {
        val id = normalizeTempId(tempFileId) ?: return null
        val path = filePath(id)
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return null
        }
        val expiresAt = readExpiresAt(id) ?: return null
        return TemporaryFileInfo(id, path, expiresAt)
    }

    fun getTemporaryFilePathIfValid(tempFileId: String, now: Long = System.currentTimeMillis()): Path? {
        val info = getTemporaryFileInfo(tempFileId) ?: return null
        return if (now < info.expiresAt) info.filePath else null
    }

    fun extendExpiry(tempFileId: String, expiresAt: Long) {
        val info = getTemporaryFileInfo(tempFileId) ?: return
        if (expiresAt > info.expiresAt) {
            writeExpiresAt(info.tempFileId, expiresAt)
        }
    }

    fun promoteTemporaryFile(tempFileId: String, destinationPath: Path) {
        val info = getTemporaryFileInfo(tempFileId) ?: throw IllegalArgumentException("Temporary file not found")
        if (System.currentTimeMillis() >= info.expiresAt) {
            throw IllegalArgumentException("Temporary file expired")
        }

        try {
            Files.move(info.filePath, destinationPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.copy(info.filePath, destinationPath, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(info.filePath)
        }

        Files.deleteIfExists(metaPath(info.tempFileId))
    }

    fun deleteTemporaryFile(tempFileId: String) {
        val id = normalizeTempId(tempFileId) ?: return
        Files.deleteIfExists(filePath(id))
        Files.deleteIfExists(metaPath(id))
    }

    fun cleanupExpiredTemporaryFiles(now: Long = System.currentTimeMillis()) {
        if (!Files.exists(tempRoot)) {
            return
        }

        val entries = mutableListOf<Path>()
        Files.list(tempRoot).use { paths ->
            paths.forEach { entries.add(it) }
        }

        val metaIds = entries
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".meta") }
            .map { it.fileName.toString().removeSuffix(".meta") }
            .toSet()

        entries
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".meta") }
            .forEach { meta ->
                val id = meta.fileName.toString().removeSuffix(".meta")
                val expiresAt = runCatching { Files.readString(meta).trim().toLong() }.getOrNull()
                if (expiresAt == null || expiresAt <= now || !Files.exists(filePath(id))) {
                    Files.deleteIfExists(filePath(id))
                    Files.deleteIfExists(meta)
                }
            }

        entries
            .filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".meta") }
            .forEach { file ->
                val id = file.fileName.toString()
                if (!metaIds.contains(id)) {
                    Files.deleteIfExists(file)
                }
            }
    }

    private fun writeExpiresAt(tempFileId: String, expiresAt: Long) {
        Files.writeString(
            metaPath(tempFileId),
            expiresAt.toString(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun readExpiresAt(tempFileId: String): Long? {
        val meta = metaPath(tempFileId)
        if (!Files.exists(meta) || !Files.isRegularFile(meta)) {
            return null
        }
        return runCatching { Files.readString(meta).trim().toLong() }.getOrNull()
    }

    private fun filePath(tempFileId: String): Path = tempRoot.resolve(tempFileId).normalize()
    private fun metaPath(tempFileId: String): Path = tempRoot.resolve("$tempFileId.meta").normalize()

    private fun normalizeTempId(tempFileId: String): String? {
        return runCatching { UUID.fromString(tempFileId).toString() }.getOrNull()
    }
}
