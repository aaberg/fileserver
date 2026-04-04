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
import net.aabergs.services.database.DatabaseFactory

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
    val databaseService = DatabaseFactory.createDatabaseService()
    val urlGenerator = UrlGenerator(publicBaseUrl, databaseService)
    
    // Start public server (port 9000)
    val publicServer = embeddedServer(CIO, port = publicPort) {
        routing {
            get("/") {
                call.respondText("Public File Server Running on port $publicPort")
            }
            publicRoutes(urlGenerator, storage)
        }
    }
    
    // Start private server (port 9001)  
    val privateServer = embeddedServer(CIO, port = privatePort) {
        routing {
            get("/") {
                call.respondText("Private API Running on port $privatePort")
            }
            privateRoutes(storage, urlGenerator)
        }
    }
    
    // Start both servers
    publicServer.start(wait = false)
    privateServer.start(wait = true)
}


