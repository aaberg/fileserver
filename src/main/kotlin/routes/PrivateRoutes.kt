package net.aabergs.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.*
import net.aabergs.models.PublicUrlRequest
import net.aabergs.models.PublicUrlResponse
import net.aabergs.services.FileStorage
import net.aabergs.services.UrlGenerator

fun Route.privateRoutes(storage: FileStorage, urlGenerator: UrlGenerator) {
    route("/file") {
        put("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val content = call.receive<ByteArray>()
            storage.storeFile(id, content)
            call.respond(HttpStatusCode.OK)
        }
        
        get("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val content = storage.getFile(id)
            if (content != null) {
                call.respondBytes(content, ContentType.Application.OctetStream)
            } else {
                call.respond(HttpStatusCode.NotFound, "File not found")
            }
        }
        
        delete("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            storage.deleteFile(id)
            call.respond(HttpStatusCode.OK)
        }
        
        post("/{id}/public-url") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val request = call.receive<PublicUrlRequest>()
            val publicUrl = urlGenerator.generatePublicUrl(id, request.duration)
            call.respond(PublicUrlResponse(publicUrl))
        }
    }
}