package net.aabergs.services

import io.ktor.http.ContentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object FileMetadataStore {
    private val json = Json
    private const val META_SUFFIX = ".meta.json"

    fun write(filePath: Path, metadata: FileMetadata) {
        Files.writeString(
            metadataPath(filePath),
            json.encodeToString(metadata),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    fun read(filePath: Path): FileMetadata? {
        val metadataPath = metadataPath(filePath)
        if (!Files.exists(metadataPath) || !Files.isRegularFile(metadataPath)) {
            return null
        }

        return runCatching {
            json.decodeFromString<FileMetadata>(Files.readString(metadataPath))
        }.getOrNull()
    }

    fun delete(filePath: Path) {
        Files.deleteIfExists(metadataPath(filePath))
    }

    fun metadataPath(filePath: Path): Path = filePath.resolveSibling(filePath.fileName.toString() + META_SUFFIX)

    fun metadataContentType(filePath: Path): String {
        return read(filePath)?.contentType?.takeIf { it.isNotBlank() }
            ?: ContentType.Application.OctetStream.toString()
    }
}
