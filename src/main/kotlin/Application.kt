package net.aabergs

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import net.aabergs.routes.privateRoutes
import net.aabergs.routes.publicRoutes
import net.aabergs.services.FileStorage
import net.aabergs.services.UrlGenerator

fun main(args: Array<String>) {
    // Start both servers
    startServers()
}

fun startServers() {
    // Load configuration
    val config = ApplicationConfig("application.yaml")
    val fileserverConfig = config.config("fileserver")
    val publicPort = fileserverConfig.property("publicPort").getString().toInt()
    val privatePort = fileserverConfig.property("privatePort").getString().toInt()
    val publicBaseUrl = fileserverConfig.property("publicBaseUrl").getString()
    val storageDirectory = fileserverConfig.property("storageDirectory").getString()
    
    // Initialize shared services
    val storage = FileStorage(storageDirectory)
    val urlGenerator = UrlGenerator(publicBaseUrl)
    
    // Start public server (port 9000)
    val publicServer = embeddedServer(CIO, port = publicPort) {
        module(publicPort, storage, urlGenerator)
    }
    
    // Start private server (port 9001)  
    val privateServer = embeddedServer(CIO, port = privatePort) {
        module(privatePort, storage, urlGenerator)
    }
    
    // Start both servers
    publicServer.start(wait = false)
    privateServer.start(wait = true)
}

fun Application.module(serverPort: Int, storage: FileStorage, urlGenerator: UrlGenerator) {
    // Configure routing based on which port we're on
    routing {
        get("/") {
            call.respondText("File Server Running on port $serverPort")
        }
        
        if (serverPort == 9000) {
            // Public routes only on port 9000
            publicRoutes(urlGenerator, storage)
        } else {
            // Private routes only on port 9001
            privateRoutes(storage, urlGenerator)
        }
    }
}


