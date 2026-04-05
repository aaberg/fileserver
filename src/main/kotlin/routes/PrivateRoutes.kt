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

private val FILE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
const val DEFAULT_MAX_UPLOAD_BYTES: Long = 10L * 1024 * 1024

private fun ApplicationCall.requireValidFileId(): String {
    val id = parameters["id"] ?: throw BadRequestException("Missing id")
    if (!FILE_ID_PATTERN.matches(id) || id == "." || id == "..") {
        throw BadRequestException("Invalid id format")
    }
    return id
}

fun Route.privateRoutes(
    storage: FileStorage,
    urlGenerator: UrlGenerator,
    maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES
) {
    route("/file") {
        put("/{id}") {
            val id = call.requireValidFileId()
            try {
                storage.storeFileFromStream(id, call.receiveStream(), maxUploadBytes)
                call.respond(HttpStatusCode.OK)
            } catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, "Upload too large")
            }
        }
        
        get("/{id}") {
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
            val id = call.requireValidFileId()
            storage.deleteFile(id)
            call.respond(HttpStatusCode.OK)
        }
        
        post("/{id}/public-url") {
            val id = call.requireValidFileId()
            val request = call.receive<PublicUrlRequest>()
            val publicUrl = urlGenerator.generatePublicUrl(id, request.duration)
            call.respond(PublicUrlResponse(publicUrl))
        }
    }
}
