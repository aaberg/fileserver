package net.aabergs.services

import net.aabergs.services.database.DatabaseService
import java.util.*

data class PublicUrlInfo(val fileRef: String, val expiresAt: Long)
data class GeneratedPublicUrl(val url: String, val expiresAt: Long)
data class FileReference(val type: FileReferenceType, val id: String)
enum class FileReferenceType { PERMANENT, TEMPORARY }

class UrlGenerator(private val baseUrl: String, private val databaseService: DatabaseService) {
    companion object {
        private const val PERMANENT_PREFIX = "perm:"
        private const val TEMPORARY_PREFIX = "tmp:"
    }

    init {
        databaseService.initialize()
    }

    fun toPermanentReference(fileId: String): String = "$PERMANENT_PREFIX$fileId"
    fun toTemporaryReference(tempFileId: String): String = "$TEMPORARY_PREFIX$tempFileId"

    fun parseReference(fileRef: String): FileReference {
        return when {
            fileRef.startsWith(PERMANENT_PREFIX) -> FileReference(FileReferenceType.PERMANENT, fileRef.removePrefix(PERMANENT_PREFIX))
            fileRef.startsWith(TEMPORARY_PREFIX) -> FileReference(FileReferenceType.TEMPORARY, fileRef.removePrefix(TEMPORARY_PREFIX))
            else -> FileReference(FileReferenceType.PERMANENT, fileRef)
        }
    }

    private fun generatePublicUrlForReference(fileRef: String, durationMinutes: Long): GeneratedPublicUrl {
        val publicId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + durationMinutes * 60 * 1000
        databaseService.insertPublicUrl(publicId, fileRef, expiresAt)
        return GeneratedPublicUrl("$baseUrl/$publicId", expiresAt)
    }

    fun generatePublicUrl(fileId: String, durationMinutes: Long): String {
        return generatePublicUrlForReference(toPermanentReference(fileId), durationMinutes).url
    }

    fun generateTemporaryPublicUrl(tempFileId: String, durationMinutes: Long): GeneratedPublicUrl {
        return generatePublicUrlForReference(toTemporaryReference(tempFileId), durationMinutes)
    }
    
    fun isPublicUrlValid(publicId: String): Boolean {
        return databaseService.getPublicUrlInfo(publicId)?.let { 
            System.currentTimeMillis() < it.expiresAt
        } ?: false
    }
    
    fun getFileIdForPublicId(publicId: String): String? {
        val info = databaseService.getPublicUrlInfo(publicId) ?: return null
        val ref = parseReference(info.fileRef)
        return if (ref.type == FileReferenceType.PERMANENT) ref.id else null
    }

    fun getPublicUrlInfo(publicId: String): PublicUrlInfo? {
        return databaseService.getPublicUrlInfo(publicId)
    }

    fun promoteTemporaryReferences(tempFileId: String, fileId: String) {
        databaseService.updateFileReferences(toTemporaryReference(tempFileId), toPermanentReference(fileId))
    }
    
    // Optional: cleanup expired URLs
    fun cleanupExpired() {
        databaseService.cleanupExpired()
    }
    
    fun close() {
        databaseService.close()
    }
}
