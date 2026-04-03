package net.aabergs.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.*
import net.aabergs.services.FileStorage
import net.aabergs.services.UrlGenerator

fun Route.publicRoutes(urlGenerator: UrlGenerator, storage: FileStorage) {
    get("/{publicId}") {
        val publicId = call.parameters["publicId"] ?: throw BadRequestException("Missing publicId")
        
        if (!urlGenerator.isPublicUrlValid(publicId)) {
            call.respond(HttpStatusCode.NotFound, "File not found or expired")
            return@get
        }
        
        val fileId = urlGenerator.getFileIdForPublicId(publicId) ?: run {
            call.respond(HttpStatusCode.NotFound, "File not found")
            return@get
        }
        val fileContent = storage.getFile(fileId)
        
        if (fileContent != null) {
            call.respondBytes(fileContent, ContentType.Application.OctetStream)
        } else {
            call.respond(HttpStatusCode.NotFound, "File not found")
        }
    }
}