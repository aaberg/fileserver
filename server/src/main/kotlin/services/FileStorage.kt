package net.aabergs.services

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class FileStorage(private val storageDirectory: String) {
    companion object {
        private val FILE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
    }

    private val storagePath: Path = File(storageDirectory).toPath().toAbsolutePath().normalize()

    init {
        Files.createDirectories(storagePath)
    }

    private fun resolveSafePath(id: String): Path {
        if (!FILE_ID_PATTERN.matches(id) || id == "." || id == "..") {
            throw IllegalArgumentException("Invalid file id")
        }
        val resolved = storagePath.resolve(id).normalize()
        if (!resolved.startsWith(storagePath)) {
            throw IllegalArgumentException("Invalid file id")
        }
        return resolved
    }
    
    fun storeFile(id: String, content: ByteArray) {
        val filePath = resolveSafePath(id)
        Files.write(
            filePath,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    fun storeFileFromStream(id: String, input: InputStream, maxUploadBytes: Long) {
        val filePath = resolveSafePath(id)
        StreamFileWriter.writeStreamAtomically(storagePath, filePath, input, maxUploadBytes)
    }
    
    fun getFile(id: String): ByteArray? {
        val filePath = resolveSafePath(id)
        return if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            Files.readAllBytes(filePath)
        } else {
            null
        }
    }

    fun getFilePath(id: String): Path? {
        val filePath = resolveSafePath(id)
        return if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            filePath
        } else {
            null
        }
    }
    
    fun deleteFile(id: String) {
        val filePath = resolveSafePath(id)
        if (!Files.deleteIfExists(filePath) && Files.exists(filePath)) {
            throw IOException("Failed to delete file: $filePath")
        }
    }

    fun getDestinationPath(id: String): Path {
        return resolveSafePath(id)
    }
}

class PayloadTooLargeException(message: String) : RuntimeException(message)
