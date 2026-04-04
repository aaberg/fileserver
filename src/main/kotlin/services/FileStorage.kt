package net.aabergs.services

import java.io.File
import java.io.IOException

class FileStorage(private val storageDirectory: String) {
    init {
        File(storageDirectory).mkdirs()
    }
    
    fun storeFile(id: String, content: ByteArray) {
        File("$storageDirectory/$id").writeBytes(content)
    }
    
    fun getFile(id: String): ByteArray? {
        val file = File("$storageDirectory/$id")
        return if (file.exists()) file.readBytes() else null
    }
    
    fun deleteFile(id: String) {
        val file = File("$storageDirectory/$id")
        if (!file.delete() && file.exists()) {
            throw IOException("Failed to delete file: ${file.absolutePath}")
        }
    }
}