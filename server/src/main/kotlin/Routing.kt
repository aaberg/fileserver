package net.aabergs

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.aabergs.routes.privateRoutes
import net.aabergs.routes.publicRoutes
import net.aabergs.services.FileStorage
import net.aabergs.services.TemporaryFileStorage
import net.aabergs.services.UrlGenerator

fun Application.configureRouting(
    storage: FileStorage,
    temporaryStorage: TemporaryFileStorage,
    urlGenerator: UrlGenerator,
    privateApiToken: String
) {
    routing {
        get("/") {
            call.respondText("File Server Running")
        }
        
        // Public routes
        publicRoutes(urlGenerator, storage, temporaryStorage)
        
        // Private routes  
        privateRoutes(storage, temporaryStorage, urlGenerator, privateApiToken)
    }
}
