package net.aabergs

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationConfigTest {
    @Test
    fun `uses yaml publicBaseUrl when no override is set`() {
        val config = MapApplicationConfig(
            "fileserver.publicBaseUrl" to "http://localhost:9000"
        ).config("fileserver")

        val resolved = resolvePublicBaseUrl(
            fileserverConfig = config,
            propertyLookup = { null },
            envLookup = { null }
        )

        assertEquals("http://localhost:9000", resolved)
    }

    @Test
    fun `uses system property override and normalizes trailing slash`() {
        val config = MapApplicationConfig(
            "fileserver.publicBaseUrl" to "http://localhost:9000"
        ).config("fileserver")

        val resolved = resolvePublicBaseUrl(
            fileserverConfig = config,
            propertyLookup = { key -> if (key == "FILESERVER_PUBLIC_BASE_URL") "https://files.example.com/" else null },
            envLookup = { null }
        )

        assertEquals("https://files.example.com", resolved)
    }

    @Test
    fun `uses env override when property is not set`() {
        val config = MapApplicationConfig(
            "fileserver.publicBaseUrl" to "http://localhost:9000"
        ).config("fileserver")

        val resolved = resolvePublicBaseUrl(
            fileserverConfig = config,
            propertyLookup = { null },
            envLookup = { key -> if (key == "FILESERVER_PUBLIC_BASE_URL") "https://cdn.example.com" else null }
        )

        assertEquals("https://cdn.example.com", resolved)
    }

    @Test
    fun `throws on invalid public base url`() {
        val config = MapApplicationConfig(
            "fileserver.publicBaseUrl" to "localhost:9000"
        ).config("fileserver")

        assertFailsWith<IllegalStateException> {
            resolvePublicBaseUrl(
                fileserverConfig = config,
                propertyLookup = { null },
                envLookup = { null }
            )
        }
    }

    @Test
    fun `uses yaml storageDirectory when no override is set`() {
        val config = MapApplicationConfig(
            "fileserver.storageDirectory" to "/tmp/fileserver"
        ).config("fileserver")

        val resolved = resolveStorageDirectory(
            fileserverConfig = config,
            propertyLookup = { null },
            envLookup = { null }
        )

        assertEquals("/tmp/fileserver", resolved)
    }

    @Test
    fun `uses system property storage override and normalizes trailing slash`() {
        val config = MapApplicationConfig(
            "fileserver.storageDirectory" to "/tmp/fileserver"
        ).config("fileserver")

        val resolved = resolveStorageDirectory(
            fileserverConfig = config,
            propertyLookup = { key -> if (key == "FILESERVER_STORAGE_DIRECTORY") "/data/files/" else null },
            envLookup = { null }
        )

        assertEquals("/data/files", resolved)
    }

    @Test
    fun `uses env storage override when property is not set`() {
        val config = MapApplicationConfig(
            "fileserver.storageDirectory" to "/tmp/fileserver"
        ).config("fileserver")

        val resolved = resolveStorageDirectory(
            fileserverConfig = config,
            propertyLookup = { null },
            envLookup = { key -> if (key == "FILESERVER_STORAGE_DIRECTORY") "/mnt/files" else null }
        )

        assertEquals("/mnt/files", resolved)
    }

    @Test
    fun `throws on blank storage directory`() {
        val config = MapApplicationConfig(
            "fileserver.storageDirectory" to "   "
        ).config("fileserver")

        assertFailsWith<IllegalStateException> {
            resolveStorageDirectory(
                fileserverConfig = config,
                propertyLookup = { null },
                envLookup = { null }
            )
        }
    }

    @Test
    fun `uses cleanup defaults when no override is set`() {
        val config = MapApplicationConfig().config("fileserver")

        val resolved = resolveCleanupConfig(
            fileserverConfig = config,
            propertyLookup = { null },
            envLookup = { null }
        )

        assertEquals(false, resolved.enabled)
        assertEquals(300L, resolved.intervalSeconds)
    }

    @Test
    fun `uses cleanup property overrides`() {
        val config = MapApplicationConfig(
            "fileserver.cleanup.enabled" to "false",
            "fileserver.cleanup.intervalSeconds" to "600"
        ).config("fileserver")

        val resolved = resolveCleanupConfig(
            fileserverConfig = config,
            propertyLookup = { key ->
                when (key) {
                    "FILESERVER_CLEANUP_ENABLED" -> "true"
                    "FILESERVER_CLEANUP_INTERVAL_SECONDS" -> "120"
                    else -> null
                }
            },
            envLookup = { null }
        )

        assertEquals(true, resolved.enabled)
        assertEquals(120L, resolved.intervalSeconds)
    }

    @Test
    fun `uses cleanup env overrides when property is not set`() {
        val config = MapApplicationConfig(
            "fileserver.cleanup.enabled" to "false",
            "fileserver.cleanup.intervalSeconds" to "600"
        ).config("fileserver")

        val resolved = resolveCleanupConfig(
            fileserverConfig = config,
            propertyLookup = { null },
            envLookup = { key ->
                when (key) {
                    "FILESERVER_CLEANUP_ENABLED" -> "true"
                    "FILESERVER_CLEANUP_INTERVAL_SECONDS" -> "180"
                    else -> null
                }
            }
        )

        assertEquals(true, resolved.enabled)
        assertEquals(180L, resolved.intervalSeconds)
    }

    @Test
    fun `throws on invalid cleanup enabled value`() {
        val config = MapApplicationConfig(
            "fileserver.cleanup.enabled" to "not-bool"
        ).config("fileserver")

        assertFailsWith<IllegalStateException> {
            resolveCleanupConfig(
                fileserverConfig = config,
                propertyLookup = { null },
                envLookup = { null }
            )
        }
    }

    @Test
    fun `throws on invalid cleanup interval`() {
        val config = MapApplicationConfig(
            "fileserver.cleanup.intervalSeconds" to "0"
        ).config("fileserver")

        assertFailsWith<IllegalStateException> {
            resolveCleanupConfig(
                fileserverConfig = config,
                propertyLookup = { null },
                envLookup = { null }
            )
        }
    }

    @Test
    fun `uses temp defaults when no override is set`() {
        val config = MapApplicationConfig().config("fileserver")

        val resolved = resolveTempFilesConfig(
            fileserverConfig = config,
            defaultMaxUploadBytes = 10_485_760,
            propertyLookup = { null },
            envLookup = { null }
        )

        assertEquals(3600L, resolved.ttlSeconds)
        assertEquals(10_485_760L, resolved.maxUploadBytes)
    }

    @Test
    fun `uses temp property overrides`() {
        val config = MapApplicationConfig(
            "fileserver.temp.ttlSeconds" to "600",
            "fileserver.temp.maxUploadBytes" to "2048"
        ).config("fileserver")

        val resolved = resolveTempFilesConfig(
            fileserverConfig = config,
            defaultMaxUploadBytes = 10_485_760,
            propertyLookup = { key ->
                when (key) {
                    "FILESERVER_TEMP_TTL_SECONDS" -> "120"
                    "FILESERVER_TEMP_MAX_UPLOAD_BYTES" -> "1024"
                    else -> null
                }
            },
            envLookup = { null }
        )

        assertEquals(120L, resolved.ttlSeconds)
        assertEquals(1024L, resolved.maxUploadBytes)
    }
}
