package net.aabergs.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.aabergs.respondJsonError
import net.aabergs.models.PublicUrlRequest
import net.aabergs.models.PublicUrlResponse
import net.aabergs.models.UpdateFileRequest
import net.aabergs.models.UpdateFileResponse
import net.aabergs.services.FileStorage
import net.aabergs.services.PayloadTooLargeException
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseService
import java.security.MessageDigest

private val FILE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
const val DEFAULT_MAX_UPLOAD_BYTES: Long = 10L * 1024 * 1024
const val DEFAULT_TEMP_FILE_TTL_SECONDS: Long = 12 * 60 * 60
const val MAX_TEMP_FILE_TTL_SECONDS: Long = 24 * 60 * 60
private val ROUTE_JSON = Json

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

private suspend fun ApplicationCall.respondUnauthorized(): Boolean {
    respondJsonError(HttpStatusCode.Unauthorized, "unauthorized", "Unauthorized")
    return false
}

private suspend fun ApplicationCall.requirePrivateApiAuth(privateApiToken: String): Boolean {
    val authorizationHeader = request.headers[HttpHeaders.Authorization] ?: run {
        return respondUnauthorized()
    }

    if (!authorizationHeader.startsWith("Bearer ")) {
        return respondUnauthorized()
    }

    val providedToken = authorizationHeader.removePrefix("Bearer ").trim()
    if (providedToken.isEmpty() || !constantTimeEquals(providedToken, privateApiToken)) {
        return respondUnauthorized()
    }

    return true
}

fun Route.privateRoutes(
    storage: FileStorage,
    urlGenerator: UrlGenerator,
    databaseService: DatabaseService,
    privateApiToken: String,
    maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    defaultTempFileTtlSeconds: Long = DEFAULT_TEMP_FILE_TTL_SECONDS,
    maxTempFileTtlSeconds: Long = MAX_TEMP_FILE_TTL_SECONDS
) {
    route("/file") {
        put("/{id}") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@put
            }
            call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let { contentLength ->
                if (contentLength > maxUploadBytes) {
                    throw PayloadTooLargeException("Upload exceeds max size of $maxUploadBytes bytes")
                }
            }
            val id = call.requireValidFileId()
            val temporary = call.request.queryParameters["temporary"]?.toBoolean() ?: false
            val ttl = call.request.queryParameters["ttl"]?.toLongOrNull() ?: defaultTempFileTtlSeconds
            val effectiveTtl = if (temporary) minOf(ttl, maxTempFileTtlSeconds) else 0L
            
            storage.storeFileFromStream(id, call.receiveStream(), maxUploadBytes)
            
            if (temporary) {
                val expiresAt = System.currentTimeMillis() + effectiveTtl * 1000
                databaseService.insertTemporaryFile(id, expiresAt)
            }
            
            call.respond(HttpStatusCode.OK)
        }
        
        patch("/{id}") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@patch
            }
            val id = call.requireValidFileId()
            val requestBody = call.receiveText()
            val request = try {
                ROUTE_JSON.decodeFromString(UpdateFileRequest.serializer(), requestBody)
            } catch (_: SerializationException) {
                throw BadRequestException("Invalid request payload")
            }
            
            val filePath = storage.getFilePath(id)
            if (filePath == null) {
                call.respond(HttpStatusCode.NotFound, "File not found")
                return@patch
            }
            
            if (!request.temporary) {
                databaseService.removeTemporaryFile(id)
            }
            
            val responseBody = ROUTE_JSON.encodeToString(
                UpdateFileResponse.serializer(),
                UpdateFileResponse(id, request.temporary)
            )
            call.respondText(responseBody, ContentType.Application.Json)
        }
        
        get("/{id}") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@get
            }
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
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@delete
            }
            val id = call.requireValidFileId()
            storage.deleteFile(id)
            call.respond(HttpStatusCode.OK)
        }
        
        post("/{id}/public-url") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@post
            }
            val id = call.requireValidFileId()
            val requestBody = call.receiveText()
            val request = try {
                ROUTE_JSON.decodeFromString(PublicUrlRequest.serializer(), requestBody)
            } catch (_: SerializationException) {
                throw BadRequestException("Invalid request payload")
            }
            val publicUrl = urlGenerator.generatePublicUrl(id, request.duration)
            val responseBody = ROUTE_JSON.encodeToString(PublicUrlResponse.serializer(), PublicUrlResponse(publicUrl))
            call.respondText(responseBody, ContentType.Application.Json)
        }
    }
}
