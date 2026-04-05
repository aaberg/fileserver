package net.aabergs.client.privateapi.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreatePublicUrlRequest(val duration: Long)
