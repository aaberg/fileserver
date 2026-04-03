package net.aabergs.services

import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class PublicUrlInfo(val fileId: String, val expiresAt: Long)

class UrlGenerator(private val baseUrl: String) {
    private val publicUrls = ConcurrentHashMap<String, PublicUrlInfo>()
    
    fun generatePublicUrl(fileId: String, durationMinutes: Long): String {
        val publicId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + durationMinutes * 60 * 1000
        publicUrls[publicId] = PublicUrlInfo(fileId, expiresAt)
        return "$baseUrl/$publicId"
    }
    
    fun isPublicUrlValid(publicId: String): Boolean {
        return publicUrls[publicId]?.let { 
            System.currentTimeMillis() < it.expiresAt
        } ?: false
    }
    
    fun getFileIdForPublicId(publicId: String): String? {
        return publicUrls[publicId]?.fileId
    }
    
    // Optional: cleanup expired URLs
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        publicUrls.entries.removeIf { it.value.expiresAt < now }
    }
}