package net.aabergs.services

import java.io.File

class FileStorage(private val storageDirectory: String) {
    init {
        File(storageDirectory).mkdirs()
    }
    
    fun storeFile(id: String, content: ByteArray): Boolean {
        return try {
            File("$storageDirectory/$id").writeBytes(content)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun getFile(id: String): ByteArray? {
        val file = File("$storageDirectory/$id")
        return if (file.exists()) file.readBytes() else null
    }
    
    fun deleteFile(id: String): Boolean {
        return File("$storageDirectory/$id").delete()
    }
}