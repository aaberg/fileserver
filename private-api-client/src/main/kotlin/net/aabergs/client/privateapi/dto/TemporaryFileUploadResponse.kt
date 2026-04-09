package net.aabergs.client.privateapi.dto

import kotlinx.serialization.Serializable

@Serializable
data class TemporaryFileUploadResponse(
    val tempFileId: String,
    val expiresAt: Long
)
