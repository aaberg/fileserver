package net.aabergs

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.aabergs.routes.privateRoutes
import net.aabergs.routes.publicRoutes
import net.aabergs.routes.DEFAULT_MAX_UPLOAD_BYTES
import net.aabergs.routes.DEFAULT_TEMP_FILE_TTL_SECONDS
import net.aabergs.routes.MAX_TEMP_FILE_TTL_SECONDS
import net.aabergs.services.FileStorage
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseService

fun Application.configureRouting(storage: FileStorage, urlGenerator: UrlGenerator, databaseService: DatabaseService, privateApiToken: String) {
    routing {
        get("/") {
            call.respondText("File Server Running")
        }
        
        // Public routes
        publicRoutes(urlGenerator, storage)
        
        // Private routes  
        privateRoutes(storage, urlGenerator, databaseService, privateApiToken, DEFAULT_MAX_UPLOAD_BYTES, DEFAULT_TEMP_FILE_TTL_SECONDS, MAX_TEMP_FILE_TTL_SECONDS)
    }
}
