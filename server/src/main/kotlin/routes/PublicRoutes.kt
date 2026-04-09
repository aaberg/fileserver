package net.aabergs.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.*
import net.aabergs.services.FileStorage
import net.aabergs.services.FileReferenceType
import net.aabergs.services.TemporaryFileStorage
import net.aabergs.services.UrlGenerator
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val HTTP_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

private fun toHttpDate(epochMillis: Long): String {
    return HTTP_DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))
}

fun Route.publicRoutes(urlGenerator: UrlGenerator, storage: FileStorage, temporaryStorage: TemporaryFileStorage) {
    get("/{publicId}") {
        val publicId = call.parameters["publicId"] ?: throw BadRequestException("Missing publicId")

        val urlInfo = urlGenerator.getPublicUrlInfo(publicId) ?: run {
            call.respond(HttpStatusCode.NotFound, "File not found or expired")
            return@get
        }

        val now = System.currentTimeMillis()
        if (now >= urlInfo.expiresAt) {
            call.respond(HttpStatusCode.NotFound, "File not found or expired")
            return@get
        }

        val fileRef = urlGenerator.parseReference(urlInfo.fileId)
        val filePath = when (fileRef.type) {
            FileReferenceType.PERMANENT -> storage.getFilePath(fileRef.id)
            FileReferenceType.TEMPORARY -> temporaryStorage.getTemporaryFilePathIfValid(fileRef.id, now)
        }

        if (filePath != null) {
            val file = filePath.toFile()
            val remainingSeconds = ((urlInfo.expiresAt - now) / 1000).coerceAtLeast(0)
            val etag = "\"${file.length()}-${file.lastModified()}\""

            call.response.header(
                HttpHeaders.CacheControl,
                "public, max-age=$remainingSeconds, must-revalidate"
            )
            call.response.header(HttpHeaders.Expires, toHttpDate(urlInfo.expiresAt))
            call.response.header(HttpHeaders.ETag, etag)
            call.response.header(HttpHeaders.LastModified, toHttpDate(file.lastModified()))
            call.response.header("X-Content-Type-Options", "nosniff")

            call.respondFile(file)
        } else {
            call.respond(HttpStatusCode.NotFound, "File not found")
        }
    }
}
