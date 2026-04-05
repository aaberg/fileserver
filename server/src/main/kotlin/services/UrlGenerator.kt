package net.aabergs.services

import net.aabergs.services.database.DatabaseService
import java.util.*

data class PublicUrlInfo(val fileId: String, val expiresAt: Long)

class UrlGenerator(private val baseUrl: String, private val databaseService: DatabaseService) {
    init {
        databaseService.initialize()
    }
    
    fun generatePublicUrl(fileId: String, durationMinutes: Long): String {
        val publicId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + durationMinutes * 60 * 1000
        databaseService.insertPublicUrl(publicId, fileId, expiresAt)
        return "$baseUrl/$publicId"
    }
    
    fun isPublicUrlValid(publicId: String): Boolean {
        return databaseService.getPublicUrlInfo(publicId)?.let { 
            System.currentTimeMillis() < it.expiresAt
        } ?: false
    }
    
    fun getFileIdForPublicId(publicId: String): String? {
        return databaseService.getPublicUrlInfo(publicId)?.fileId
    }
    
    // Optional: cleanup expired URLs
    fun cleanupExpired() {
        databaseService.cleanupExpired()
    }
    
    fun close() {
        databaseService.close()
    }
}
