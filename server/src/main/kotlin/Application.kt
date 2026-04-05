package net.aabergs

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.serialization.kotlinx.json.*
import net.aabergs.routes.privateRoutes
import net.aabergs.routes.publicRoutes
import net.aabergs.routes.DEFAULT_MAX_UPLOAD_BYTES
import net.aabergs.models.ErrorResponse
import net.aabergs.services.FileStorage
import net.aabergs.services.PayloadTooLargeException
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseFactory

private data class ServerTimeouts(
    val shutdownGracePeriodMillis: Long,
    val shutdownTimeoutMillis: Long
)

fun Application.configureCommonPlugins() {
    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(
                io.ktor.http.HttpStatusCode.BadRequest,
                ErrorResponse("bad_request", cause.message ?: "Bad request")
            )
        }
        exception<PayloadTooLargeException> { call, cause ->
            call.respond(
                io.ktor.http.HttpStatusCode.PayloadTooLarge,
                ErrorResponse("payload_too_large", cause.message ?: "Payload too large")
            )
        }
        exception<Throwable> { call, _ ->
            call.respond(
                io.ktor.http.HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", "Internal server error")
            )
        }
    }
}

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
    val maxUploadBytes = fileserverConfig.propertyOrNull("maxUploadBytes")?.getString()?.toLong()
        ?: DEFAULT_MAX_UPLOAD_BYTES
    val timeoutsConfig = fileserverConfig.config("timeouts")
    val serverTimeouts = ServerTimeouts(
        shutdownGracePeriodMillis = timeoutsConfig.propertyOrNull("shutdownGracePeriodMillis")
            ?.getString()?.toLong() ?: 5_000,
        shutdownTimeoutMillis = timeoutsConfig.propertyOrNull("shutdownTimeoutMillis")
            ?.getString()?.toLong() ?: 15_000
    )
    val privateApiToken = System.getenv("PRIVATE_API_TOKEN")?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("PRIVATE_API_TOKEN must be set")
    
    // Initialize shared services
    val storage = FileStorage(storageDirectory)
    val databaseService = DatabaseFactory.createDatabaseService()
    val urlGenerator = UrlGenerator(publicBaseUrl, databaseService)

    // Start public server (port 9000)
    val publicServer = embeddedServer(CIO, port = publicPort) {
        configurePublicServer(urlGenerator, storage)
    }
    
    // Start private server (port 9001)  
    val privateServer = embeddedServer(CIO, port = privatePort) {
        configurePrivateServer(urlGenerator, storage, privateApiToken, maxUploadBytes)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            privateServer.stop(
                serverTimeouts.shutdownGracePeriodMillis,
                serverTimeouts.shutdownTimeoutMillis
            )
            publicServer.stop(
                serverTimeouts.shutdownGracePeriodMillis,
                serverTimeouts.shutdownTimeoutMillis
            )
        } finally {
            urlGenerator.close()
        }
    })
    
    // Start both servers
    publicServer.start(wait = false)
    privateServer.start(wait = true)
}

fun Application.configurePublicServer(urlGenerator: UrlGenerator, storage: FileStorage) {
    configureCommonPlugins()

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        publicRoutes(urlGenerator, storage)
    }
}

fun Application.configurePrivateServer(
    urlGenerator: UrlGenerator,
    storage: FileStorage,
    privateApiToken: String,
    maxUploadBytes: Long
) {
    configureCommonPlugins()

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        privateRoutes(storage, urlGenerator, privateApiToken, maxUploadBytes)
    }
}
