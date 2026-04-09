package net.aabergs.services

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal object StreamFileWriter {
    fun writeStreamAtomically(
        rootDirectory: Path,
        destinationPath: Path,
        input: InputStream,
        maxUploadBytes: Long
    ) {
        val tempPath = Files.createTempFile(rootDirectory, "upload-", ".tmp")

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

            Files.move(tempPath, destinationPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tempPath)
            throw e
        }
    }
}
