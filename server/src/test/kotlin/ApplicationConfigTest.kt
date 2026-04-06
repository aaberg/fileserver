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
}
