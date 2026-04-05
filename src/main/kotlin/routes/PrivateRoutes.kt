package net.aabergs.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.*
import net.aabergs.models.PublicUrlRequest
import net.aabergs.models.PublicUrlResponse
import net.aabergs.services.FileStorage
import net.aabergs.services.PayloadTooLargeException
import net.aabergs.services.UrlGenerator
import java.security.MessageDigest

private val FILE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
const val DEFAULT_MAX_UPLOAD_BYTES: Long = 10L * 1024 * 1024
class UnauthorizedAccessException : RuntimeException("Unauthorized")

private fun ApplicationCall.requireValidFileId(): String {
    val id = parameters["id"] ?: throw BadRequestException("Missing id")
    if (!FILE_ID_PATTERN.matches(id) || id == "." || id == "..") {
        throw BadRequestException("Invalid id format")
    }
    return id
}

private fun constantTimeEquals(left: String, right: String): Boolean {
    // Compare in constant time to reduce token timing side-channel leakage.
    return MessageDigest.isEqual(left.toByteArray(), right.toByteArray())
}

private suspend fun ApplicationCall.requirePrivateApiAuth(privateApiToken: String): Boolean {
    val authorizationHeader = request.headers[HttpHeaders.Authorization] ?: run {
        throw UnauthorizedAccessException()
    }

    if (!authorizationHeader.startsWith("Bearer ")) {
        throw UnauthorizedAccessException()
    }

    val providedToken = authorizationHeader.removePrefix("Bearer ").trim()
    if (providedToken.isEmpty() || !constantTimeEquals(providedToken, privateApiToken)) {
        throw UnauthorizedAccessException()
    }

    return true
}

fun Route.privateRoutes(
    storage: FileStorage,
    urlGenerator: UrlGenerator,
    privateApiToken: String,
    maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES
) {
    route("/file") {
        put("/{id}") {
            call.requirePrivateApiAuth(privateApiToken)
            call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let { contentLength ->
                if (contentLength > maxUploadBytes) {
                    throw PayloadTooLargeException("Upload exceeds max size of $maxUploadBytes bytes")
                }
            }
            val id = call.requireValidFileId()
            storage.storeFileFromStream(id, call.receiveStream(), maxUploadBytes)
            call.respond(HttpStatusCode.OK)
        }
        
        get("/{id}") {
            call.requirePrivateApiAuth(privateApiToken)
            val id = call.requireValidFileId()
            val filePath = storage.getFilePath(id)
            if (filePath != null) {
                val file = filePath.toFile()
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString()
                )
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondOutputStream(ContentType.Application.OctetStream) {
                    file.inputStream().use { input -> input.copyTo(this) }
                }
            } else {
                call.respond(HttpStatusCode.NotFound, "File not found")
            }
        }
        
        delete("/{id}") {
            call.requirePrivateApiAuth(privateApiToken)
            val id = call.requireValidFileId()
            storage.deleteFile(id)
            call.respond(HttpStatusCode.OK)
        }
        
        post("/{id}/public-url") {
            call.requirePrivateApiAuth(privateApiToken)
            val id = call.requireValidFileId()
            val request = call.receive<PublicUrlRequest>()
            val publicUrl = urlGenerator.generatePublicUrl(id, request.duration)
            call.respond(PublicUrlResponse(publicUrl))
        }
    }
}
