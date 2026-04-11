package net.aabergs

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
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
import net.aabergs.models.ErrorResponse
import net.aabergs.services.FileStorage
import net.aabergs.services.PayloadTooLargeException
import net.aabergs.services.TemporaryFileStorage
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseFactory
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private data class ServerTimeouts(
    val shutdownGracePeriodMillis: Long,
    val shutdownTimeoutMillis: Long
)

internal data class CleanupConfig(
    val enabled: Boolean,
    val intervalSeconds: Long
)

internal data class TempFilesConfig(
    val ttlSeconds: Long,
    val maxUploadBytes: Long
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

internal fun resolveCleanupConfig(
    fileserverConfig: ApplicationConfig,
    propertyLookup: (String) -> String? = System::getProperty,
    envLookup: (String) -> String? = System::getenv
): CleanupConfig {
    fun parseBoolean(value: String): Boolean {
        return when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalStateException("Invalid cleanup enabled value: $value")
        }
    }

    val enabled = propertyLookup("FILESERVER_CLEANUP_ENABLED")?.takeIf { it.isNotBlank() }
        ?.let(::parseBoolean)
        ?: envLookup("FILESERVER_CLEANUP_ENABLED")?.takeIf { it.isNotBlank() }
            ?.let(::parseBoolean)
        ?: fileserverConfig.propertyOrNull("cleanup.enabled")?.getString()?.let(::parseBoolean)
        ?: false

    val intervalSeconds = propertyLookup("FILESERVER_CLEANUP_INTERVAL_SECONDS")?.takeIf { it.isNotBlank() }
        ?.toLongOrNull()
        ?: envLookup("FILESERVER_CLEANUP_INTERVAL_SECONDS")?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
        ?: fileserverConfig.propertyOrNull("cleanup.intervalSeconds")?.getString()?.toLongOrNull()
        ?: 300L

    if (intervalSeconds <= 0) {
        throw IllegalStateException("Cleanup interval must be greater than zero")
    }

    return CleanupConfig(enabled, intervalSeconds)
}

internal fun resolveTempFilesConfig(
    fileserverConfig: ApplicationConfig,
    defaultMaxUploadBytes: Long,
    propertyLookup: (String) -> String? = System::getProperty,
    envLookup: (String) -> String? = System::getenv
): TempFilesConfig {
    val ttlSeconds = propertyLookup("FILESERVER_TEMP_TTL_SECONDS")?.takeIf { it.isNotBlank() }
        ?.toLongOrNull()
        ?: envLookup("FILESERVER_TEMP_TTL_SECONDS")?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
        ?: fileserverConfig.propertyOrNull("temp.ttlSeconds")?.getString()?.toLongOrNull()
        ?: 3600L

    val maxUploadBytes = propertyLookup("FILESERVER_TEMP_MAX_UPLOAD_BYTES")?.takeIf { it.isNotBlank() }
        ?.toLongOrNull()
        ?: envLookup("FILESERVER_TEMP_MAX_UPLOAD_BYTES")?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
        ?: fileserverConfig.propertyOrNull("temp.maxUploadBytes")?.getString()?.toLongOrNull()
        ?: defaultMaxUploadBytes

    if (ttlSeconds <= 0) {
        throw IllegalStateException("Temporary file TTL must be greater than zero")
    }
    if (maxUploadBytes <= 0) {
        throw IllegalStateException("Temporary file max upload size must be greater than zero")
    }

    return TempFilesConfig(ttlSeconds, maxUploadBytes)
}

private fun startCleanupSchedulerIfEnabled(
    cleanupConfig: CleanupConfig,
    urlGenerator: UrlGenerator,
    temporaryStorage: TemporaryFileStorage
): ScheduledExecutorService? {
    if (!cleanupConfig.enabled) {
        return null
    }

    val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fileserver-cleanup").apply { isDaemon = true }
    }

    scheduler.scheduleAtFixedRate(
        {
            try {
                urlGenerator.cleanupExpired()
                temporaryStorage.cleanupExpiredTemporaryFiles()
            } catch (e: Exception) {
                System.err.println("Cleanup job failed: ${e.message}")
            }
        },
        cleanupConfig.intervalSeconds,
        cleanupConfig.intervalSeconds,
        TimeUnit.SECONDS
    )

    return scheduler
}

fun Application.configureCommonPlugins() {
    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.application.log.warn(
                "400 Bad Request: {} - {} ({})",
                call.request.local.method,
                call.request.uri,
                cause.message ?: "Bad request"
            )
            call.respondJsonError(HttpStatusCode.BadRequest, "bad_request", cause.message ?: "Bad request")
        }
        exception<PayloadTooLargeException> { call, cause ->
            call.application.log.warn(
                "413 Payload Too Large: {} - {} ({})",
                call.request.local.method,
                call.request.uri,
                cause.message ?: "Payload too large"
            )
            call.respondJsonError(
                HttpStatusCode.PayloadTooLarge,
                "payload_too_large",
                cause.message ?: "Payload too large"
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled server error: {} - {}",
                call.request.local.method,
                call.request.uri,
                cause
            )
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
    val cleanupConfig = resolveCleanupConfig(fileserverConfig)
    val maxUploadBytes = fileserverConfig.propertyOrNull("maxUploadBytes")?.getString()?.toLong()
        ?: DEFAULT_MAX_UPLOAD_BYTES
    val tempFilesConfig = resolveTempFilesConfig(fileserverConfig, maxUploadBytes)
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
    val temporaryStorage = TemporaryFileStorage(storageDirectory)
    val databaseService = DatabaseFactory.createDatabaseService()
    val urlGenerator = UrlGenerator(publicBaseUrl, databaseService)
    val cleanupScheduler = startCleanupSchedulerIfEnabled(cleanupConfig, urlGenerator, temporaryStorage)

    // Start public server (port 9000)
    val publicServer = embeddedServer(CIO, port = publicPort) {
        configurePublicServer(urlGenerator, storage, temporaryStorage)
    }
    
    // Start private server (port 9001)  
    val privateServer = embeddedServer(CIO, port = privatePort) {
        configurePrivateServer(
            urlGenerator,
            storage,
            temporaryStorage,
            privateApiToken,
            maxUploadBytes,
            tempFilesConfig.maxUploadBytes,
            tempFilesConfig.ttlSeconds
        )
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
            cleanupScheduler?.shutdownNow()
            cleanupScheduler?.awaitTermination(5, TimeUnit.SECONDS)
            urlGenerator.close()
        }
    })
    
    // Start both servers
    publicServer.start(wait = false)
    privateServer.start(wait = true)
}

fun Application.configurePublicServer(
    urlGenerator: UrlGenerator,
    storage: FileStorage,
    temporaryStorage: TemporaryFileStorage
) {
    configureCommonPlugins()

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        publicRoutes(urlGenerator, storage, temporaryStorage)
    }
}

fun Application.configurePrivateServer(
    urlGenerator: UrlGenerator,
    storage: FileStorage,
    temporaryStorage: TemporaryFileStorage,
    privateApiToken: String,
    maxUploadBytes: Long,
    tempUploadMaxBytes: Long,
    tempTtlSeconds: Long
) {
    configureCommonPlugins()

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        privateRoutes(
            storage,
            temporaryStorage,
            urlGenerator,
            privateApiToken,
            maxUploadBytes,
            tempUploadMaxBytes,
            tempTtlSeconds
        )
    }
}
