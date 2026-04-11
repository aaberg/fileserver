package net.aabergs.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.aabergs.models.PublicUrlRequest
import net.aabergs.models.PublicUrlResponse
import net.aabergs.models.TemporaryFileUploadResponse
import net.aabergs.respondJsonError
import net.aabergs.services.*
import net.aabergs.services.PayloadTooLargeException
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.MessageDigest

private val FILE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
const val DEFAULT_MAX_UPLOAD_BYTES: Long = 10L * 1024 * 1024
private val ROUTE_JSON = Json
private val DEFAULT_DOWNLOAD_CONTENT_TYPE = ContentType.Application.OctetStream

private suspend fun ApplicationCall.respondWithFileDownload(
    storedFile: StoredFileInfo?,
    notFoundMessage: String
) {
    if (storedFile != null) {
        // Re-check file existence right before streaming to handle race conditions
        // where cleanup or concurrent operations may have deleted the file
        if (!Files.isRegularFile(storedFile.filePath)) {
            respond(HttpStatusCode.NotFound, notFoundMessage)
            return
        }
        
        val file = storedFile.filePath.toFile()
        response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString()
        )
        response.header("X-Content-Type-Options", "nosniff")
        
        // Handle file disappearance during open attempt
        try {
            respondOutputStream(runCatching { ContentType.parse(storedFile.contentType) }.getOrDefault(DEFAULT_DOWNLOAD_CONTENT_TYPE)) {
                file.inputStream().use { input -> input.copyTo(this) }
            }
        } catch (e: NoSuchFileException) {
            // File was deleted between existence check and open attempt
            respond(HttpStatusCode.NotFound, notFoundMessage)
        } catch (e: FileNotFoundException) {
            // File was deleted between existence check and open attempt
            respond(HttpStatusCode.NotFound, notFoundMessage)
        }
    } else {
        respond(HttpStatusCode.NotFound, notFoundMessage)
    }
}

private fun ApplicationRequest.uploadContentTypeHeader(): String {
    val contentType = contentType().withoutParameters()
    return if (contentType == ContentType.Any || contentType.toString().isBlank()) {
        ContentType.Application.OctetStream.toString()
    } else {
        contentType.toString()
    }
}

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
    temporaryStorage: TemporaryFileStorage,
    urlGenerator: UrlGenerator,
    privateApiToken: String,
    maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    tempUploadMaxBytes: Long = maxUploadBytes,
    tempTtlSeconds: Long = 3600
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
            storage.storeFileFromStream(id, call.receiveStream(), maxUploadBytes, call.request.uploadContentTypeHeader())
            call.respond(HttpStatusCode.OK)
        }
        
        get("/{id}") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@get
            }
            val id = call.requireValidFileId()
            val storedFile = storage.getStoredFileInfo(id)
            call.respondWithFileDownload(storedFile, "File not found")
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

    route("/temp-file") {
        post {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@post
            }
            call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let { contentLength ->
                if (contentLength > tempUploadMaxBytes) {
                    throw PayloadTooLargeException("Upload exceeds max size of $tempUploadMaxBytes bytes")
                }
            }

            val info = temporaryStorage.storeTemporaryFromStream(
                call.receiveStream(),
                tempUploadMaxBytes,
                tempTtlSeconds,
                call.request.uploadContentTypeHeader()
            )
            val response = TemporaryFileUploadResponse(info.tempFileId, info.expiresAt)
            call.respondText(ROUTE_JSON.encodeToString(TemporaryFileUploadResponse.serializer(), response), ContentType.Application.Json)
        }

        delete("/{tempFileId}") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@delete
            }
            val tempFileId = call.parameters["tempFileId"] ?: throw BadRequestException("Missing tempFileId")
            temporaryStorage.deleteTemporaryFile(tempFileId)
            call.respond(HttpStatusCode.OK)
        }

        get("/{tempFileId}") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@get
            }
            val tempFileId = call.parameters["tempFileId"] ?: throw BadRequestException("Missing tempFileId")
            val storedFile = temporaryStorage.getTemporaryStoredFileInfoIfValid(tempFileId)
            call.respondWithFileDownload(storedFile, "Temporary file not found")
        }

        post("/{tempFileId}/promote/{id}") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@post
            }
            val tempFileId = call.parameters["tempFileId"] ?: throw BadRequestException("Missing tempFileId")
            val id = call.requireValidFileId()

            val destinationPath = storage.getDestinationPath(id)
            try {
                val tempInfo = temporaryStorage.promoteTemporaryFile(tempFileId, destinationPath)
                storage.writeMetadataForPath(destinationPath, tempInfo.contentType)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, "Temporary file not found")
                return@post
            }
            urlGenerator.promoteTemporaryReferences(tempFileId, id)
            call.respond(HttpStatusCode.OK)
        }

        post("/{tempFileId}/public-url") {
            if (!call.requirePrivateApiAuth(privateApiToken)) {
                return@post
            }
            val tempFileId = call.parameters["tempFileId"] ?: throw BadRequestException("Missing tempFileId")
            val tempInfo = temporaryStorage.getTemporaryFileInfo(tempFileId)
            if (tempInfo == null || System.currentTimeMillis() >= tempInfo.expiresAt) {
                call.respond(HttpStatusCode.NotFound, "Temporary file not found")
                return@post
            }

            val requestBody = call.receiveText()
            val request = try {
                ROUTE_JSON.decodeFromString(PublicUrlRequest.serializer(), requestBody)
            } catch (_: SerializationException) {
                throw BadRequestException("Invalid request payload")
            }

            val generated = urlGenerator.generateTemporaryPublicUrl(tempFileId, request.duration)
            temporaryStorage.extendExpiry(tempFileId, generated.expiresAt)

            val responseBody = ROUTE_JSON.encodeToString(
                PublicUrlResponse.serializer(),
                PublicUrlResponse(generated.url)
            )
            call.respondText(responseBody, ContentType.Application.Json)
        }
    }
}
