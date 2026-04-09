package net.aabergs.models

import kotlinx.serialization.Serializable

@Serializable
data class TemporaryFileUploadResponse(
    val tempFileId: String,
    val expiresAt: Long
)
