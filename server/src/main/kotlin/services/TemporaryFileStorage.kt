package net.aabergs.services

import io.ktor.http.ContentType
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

data class TemporaryFileInfo(
    val tempFileId: String,
    val filePath: Path,
    val expiresAt: Long,
    val contentType: String
)

class TemporaryFileStorage(storageDirectory: String) {
    private val tempRoot: Path = Path.of(storageDirectory).toAbsolutePath().normalize().resolve(".tmp")

    init {
        Files.createDirectories(tempRoot)
    }

    fun storeTemporaryFromStream(
        input: InputStream,
        maxUploadBytes: Long,
        ttlSeconds: Long,
        contentType: String = ContentType.Application.OctetStream.toString()
    ): TemporaryFileInfo {
        val tempId = UUID.randomUUID().toString()
        val filePath = filePath(tempId)
        val expiresAt = System.currentTimeMillis() + ttlSeconds * 1000

        try {
            StreamFileWriter.writeStreamAtomically(tempRoot, filePath, input, maxUploadBytes)
            FileMetadataStore.write(filePath, FileMetadata(contentType = contentType, expiresAt = expiresAt))
            return TemporaryFileInfo(tempId, filePath, expiresAt, contentType)
        } catch (e: Exception) {
            Files.deleteIfExists(filePath)
            FileMetadataStore.delete(filePath)
            throw e
        }
    }

    fun getTemporaryFileInfo(tempFileId: String): TemporaryFileInfo? {
        val id = normalizeTempId(tempFileId) ?: return null
        val path = filePath(id)
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return null
        }
        val metadata = FileMetadataStore.read(path) ?: return null
        val expiresAt = metadata.expiresAt ?: return null
        return TemporaryFileInfo(id, path, expiresAt, metadata.contentType)
    }

    fun getTemporaryFilePathIfValid(tempFileId: String, now: Long = System.currentTimeMillis()): Path? {
        val info = getTemporaryFileInfo(tempFileId) ?: return null
        return if (now < info.expiresAt) info.filePath else null
    }

    fun getTemporaryStoredFileInfoIfValid(tempFileId: String, now: Long = System.currentTimeMillis()): StoredFileInfo? {
        val info = getTemporaryFileInfo(tempFileId) ?: return null
        return if (now < info.expiresAt) {
            StoredFileInfo(info.filePath, info.contentType)
        } else {
            null
        }
    }

    fun extendExpiry(tempFileId: String, expiresAt: Long) {
        val info = getTemporaryFileInfo(tempFileId) ?: return
        if (expiresAt > info.expiresAt) {
            FileMetadataStore.write(info.filePath, FileMetadata(contentType = info.contentType, expiresAt = expiresAt))
        }
    }

    fun promoteTemporaryFile(tempFileId: String, destinationPath: Path): TemporaryFileInfo {
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

        FileMetadataStore.delete(info.filePath)
        return info
    }

    fun deleteTemporaryFile(tempFileId: String) {
        val id = normalizeTempId(tempFileId) ?: return
        val path = filePath(id)
        Files.deleteIfExists(path)
        FileMetadataStore.delete(path)
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
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".meta.json") }
            .map { it.fileName.toString().removeSuffix(".meta.json") }
            .toSet()

        entries
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".meta.json") }
            .forEach { meta ->
                val id = meta.fileName.toString().removeSuffix(".meta.json")
                val expiresAt = runCatching {
                    FileMetadataStore.read(filePath(id))?.expiresAt
                        ?: kotlinx.serialization.json.Json.decodeFromString<FileMetadata>(Files.readString(meta)).expiresAt
                }.getOrNull()
                if (expiresAt == null || expiresAt <= now || !Files.exists(filePath(id))) {
                    Files.deleteIfExists(filePath(id))
                    Files.deleteIfExists(meta)
                }
            }

        entries
            .filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".meta.json") }
            .forEach { file ->
                val id = file.fileName.toString()
                if (!metaIds.contains(id)) {
                    Files.deleteIfExists(file)
                }
            }
    }

    private fun filePath(tempFileId: String): Path = tempRoot.resolve(tempFileId).normalize()

    private fun normalizeTempId(tempFileId: String): String? {
        return runCatching { UUID.fromString(tempFileId).toString() }.getOrNull()
    }
}
