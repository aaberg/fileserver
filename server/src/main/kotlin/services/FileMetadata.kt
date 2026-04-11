package net.aabergs.services

import io.ktor.http.ContentType
import kotlinx.serialization.Serializable

@Serializable
data class FileMetadata(
    val contentType: String = ContentType.Application.OctetStream.toString(),
    val expiresAt: Long? = null
)

data class StoredFileInfo(
    val filePath: java.nio.file.Path,
    val contentType: String
)
