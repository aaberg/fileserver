package net.aabergs

import io.ktor.http.*
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.aabergs.routes.privateRoutes
import net.aabergs.routes.publicRoutes
import net.aabergs.routes.DEFAULT_MAX_UPLOAD_BYTES
import net.aabergs.routes.DEFAULT_TEMP_FILE_TTL_SECONDS
import net.aabergs.routes.MAX_TEMP_FILE_TTL_SECONDS
import net.aabergs.models.ErrorResponse
import net.aabergs.services.FileStorage
import net.aabergs.services.PayloadTooLargeException
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseFactory
import net.aabergs.services.database.DatabaseService
import java.net.URI
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

private data class ServerTimeouts(
    val shutdownGracePeriodMillis: Long,
    val shutdownTimeoutMillis: Long
)

private val ERROR_RESPONSE_JSON = Json

internal suspend fun ApplicationCall.respondJsonError(
    status: HttpStatusCode,
    error: String,
    message: String
) {
    val payload = ERROR_RESPONSE_JSON.encodeToString(ErrorResponse(error, message))
    respondText(payload, ContentType.Application.Json, status)
}

internal fun resolvePublicBaseUrl(
    fileserverConfig: ApplicationConfig,
    propertyLookup: (String) -> String? = System::getProperty,
    envLookup: (String) -> String? = System::getenv
): String {
    val configuredValue = propertyLookup("FILESERVER_PUBLIC_BASE_URL")
        ?.takeIf { it.isNotBlank() }
        ?: envLookup("FILESERVER_PUBLIC_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: fileserverConfig.property("publicBaseUrl").getString()

    val normalized = configuredValue.trim().trimEnd('/')
    if (normalized.isBlank()) {
        throw IllegalStateException("Public base URL must not be blank")
    }

    val parsed = try {
        URI(normalized)
    } catch (_: Exception) {
        throw IllegalStateException("Invalid public base URL: $configuredValue")
    }

    if ((parsed.scheme != "http" && parsed.scheme != "https") || parsed.host.isNullOrBlank()) {
        throw IllegalStateException("Invalid public base URL: $configuredValue")
    }

    return normalized
}

internal fun resolveStorageDirectory(
    fileserverConfig: ApplicationConfig,
    propertyLookup: (String) -> String? = System::getProperty,
    envLookup: (String) -> String? = System::getenv
): String {
    val configuredValue = propertyLookup("FILESERVER_STORAGE_DIRECTORY")
        ?.takeIf { it.isNotBlank() }
        ?: envLookup("FILESERVER_STORAGE_DIRECTORY")?.takeIf { it.isNotBlank() }
        ?: fileserverConfig.property("storageDirectory").getString()

    val normalized = configuredValue.trim().trimEnd('/')
    if (normalized.isBlank()) {
        throw IllegalStateException("Storage directory must not be blank")
    }

    return normalized
}

fun Application.configureCommonPlugins() {
    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondJsonError(HttpStatusCode.BadRequest, "bad_request", cause.message ?: "Bad request")
        }
        exception<PayloadTooLargeException> { call, cause ->
            call.respondJsonError(
                HttpStatusCode.PayloadTooLarge,
                "payload_too_large",
                cause.message ?: "Payload too large"
            )
        }
        exception<Throwable> { call, _ ->
            call.respondJsonError(HttpStatusCode.InternalServerError, "internal_error", "Internal server error")
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
    val publicBaseUrl = resolvePublicBaseUrl(fileserverConfig)
    val storageDirectory = resolveStorageDirectory(fileserverConfig)
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
    
    val tempFileConfig = fileserverConfig.config("temporaryFiles")
    val defaultTempFileTtlSeconds = tempFileConfig.propertyOrNull("defaultTtlSeconds")
        ?.getString()?.toLong() ?: DEFAULT_TEMP_FILE_TTL_SECONDS
    val maxTempFileTtlSeconds = tempFileConfig.propertyOrNull("maxTtlSeconds")
        ?.getString()?.toLong() ?: MAX_TEMP_FILE_TTL_SECONDS
    val runCleanupJob = System.getenv("FILESERVER_RUN_CLEANUP_JOB")?.toBoolean() ?: true
    val cleanupIntervalSeconds = tempFileConfig.propertyOrNull("cleanupIntervalSeconds")
        ?.getString()?.toLong() ?: 60L
    
    // Initialize shared services
    val storage = FileStorage(storageDirectory)
    val databaseService = DatabaseFactory.createDatabaseService()
    val urlGenerator = UrlGenerator(publicBaseUrl, databaseService)
    
    // Start cleanup job for temporary files
    if (runCleanupJob) {
        startCleanupJob(databaseService, storage, cleanupIntervalSeconds.seconds)
    }

    // Start public server (port 9000)
    val publicServer = embeddedServer(CIO, port = publicPort) {
        configurePublicServer(urlGenerator, storage)
    }
    
    // Start private server (port 9001)  
    val privateServer = embeddedServer(CIO, port = privatePort) {
        configurePrivateServer(urlGenerator, storage, databaseService, privateApiToken, maxUploadBytes, defaultTempFileTtlSeconds, maxTempFileTtlSeconds)
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
            databaseService.close()
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
    databaseService: DatabaseService,
    privateApiToken: String,
    maxUploadBytes: Long,
    defaultTempFileTtlSeconds: Long,
    maxTempFileTtlSeconds: Long
) {
    configureCommonPlugins()

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        privateRoutes(storage, urlGenerator, databaseService, privateApiToken, maxUploadBytes, defaultTempFileTtlSeconds, maxTempFileTtlSeconds)
    }
}

private fun startCleanupJob(databaseService: DatabaseService, storage: FileStorage, interval: kotlin.time.Duration) {
    thread(isDaemon = true) {
        while (true) {
            try {
                val expiredFileIds = databaseService.claimExpiredTemporaryFiles()
                for (fileId in expiredFileIds) {
                    try {
                        storage.deleteFile(fileId)
                        databaseService.removeTemporaryFile(fileId)
                    } catch (e: Exception) {
                        // File might already be deleted, that's okay
                    }
                }
            } catch (e: Exception) {
                // Log error but continue running
            }
            Thread.sleep(interval.inWholeMilliseconds)
        }
    }
}
