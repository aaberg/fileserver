package net.aabergs.services.database

import net.aabergs.services.PublicUrlInfo

interface DatabaseService {
    fun initialize()
    fun insertPublicUrl(publicId: String, fileId: String, expiresAt: Long)
    fun getPublicUrlInfo(publicId: String): PublicUrlInfo?
    fun cleanupExpired()
    fun close()
}