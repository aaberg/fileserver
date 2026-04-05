package net.aabergs.routes

import io.ktor.http.*
import io.ktor.server.application.*
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
        val filePath = storage.getFilePath(fileId)
        
        if (filePath != null) {
            call.respondFile(filePath.toFile())
        } else {
            call.respond(HttpStatusCode.NotFound, "File not found")
        }
    }
}
