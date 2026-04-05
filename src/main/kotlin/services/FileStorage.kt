package net.aabergs.services

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        val tempPath = Files.createTempFile(storagePath, "upload-", ".tmp")

        try {
            input.use { stream ->
                Files.newOutputStream(
                    tempPath,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                ).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L

                    while (true) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead == -1) {
                            break
                        }

                        totalBytes += bytesRead
                        if (totalBytes > maxUploadBytes) {
                            throw PayloadTooLargeException("Upload exceeds max size of $maxUploadBytes bytes")
                        }

                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tempPath)
            throw e
        }
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
}

class PayloadTooLargeException(message: String) : RuntimeException(message)
