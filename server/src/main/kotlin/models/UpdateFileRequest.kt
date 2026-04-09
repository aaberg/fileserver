package net.aabergs.models

import kotlinx.serialization.Serializable

@Serializable
data class UpdateFileRequest(val temporary: Boolean = false)

@Serializable
data class UpdateFileResponse(val id: String, val temporary: Boolean)
