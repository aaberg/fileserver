package net.aabergs.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.routing.*
import org.junit.*
import org.junit.Assert.*
import net.aabergs.configurePublicServer
import net.aabergs.services.FileStorage
import net.aabergs.services.TemporaryFileStorage
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseFactory
import java.io.File
import java.nio.file.Files

class PublicRoutesTest {
    private lateinit var storage: FileStorage
    private lateinit var temporaryStorage: TemporaryFileStorage
    private lateinit var urlGenerator: UrlGenerator
    private val testDir = Files.createTempDirectory("fileserver-test").toString()
    private val baseUrl = "http://localhost:9000"
    
    @Before
    fun setup() {
        storage = FileStorage(testDir)
        temporaryStorage = TemporaryFileStorage(testDir)
        // Use SQLite for tests with a temporary database
        System.setProperty("DB_TYPE", "sqlite")
        System.setProperty("DB_URL", "jdbc:sqlite::memory:")
        
        val databaseService = DatabaseFactory.createDatabaseService()
        urlGenerator = UrlGenerator(baseUrl, databaseService)
    }
    
    @After
    fun cleanup() {
        urlGenerator.close()
        File(testDir).deleteRecursively()
        // Clear environment properties
        System.clearProperty("DB_TYPE")
        System.clearProperty("DB_URL")
    }
    
    @Test
    fun testPublicRouteWithValidURL() = testApplication {
        // Setup routing
        application {
            routing {
                publicRoutes(urlGenerator, storage, temporaryStorage)
            }
        }
        
        // Store a test file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        storage.storeFile(fileId, fileContent)
        
        // Generate a public URL
        val publicUrl = urlGenerator.generatePublicUrl(fileId, 60)
        val publicId = publicUrl.substringAfterLast("/")
        
        // Test accessing the file via public URL
        val response = client.get("/$publicId")
        
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, World!", response.bodyAsText())
        val cacheControl = requireNotNull(response.headers[HttpHeaders.CacheControl])
        assertTrue(cacheControl.contains("public"))
        assertTrue(cacheControl.contains("must-revalidate"))
        val maxAge = requireNotNull(Regex("max-age=(\\d+)").find(cacheControl)?.groupValues?.get(1)?.toInt())
        assertTrue(maxAge > 0)
        assertNotNull(response.headers[HttpHeaders.Expires])
        assertNotNull(response.headers[HttpHeaders.ETag])
        assertNotNull(response.headers[HttpHeaders.LastModified])
    }
    
    @Test
    fun testPublicRouteWithExpiredURL() = testApplication {
        // Setup routing
        application {
            routing {
                publicRoutes(urlGenerator, storage, temporaryStorage)
            }
        }
        
        // Store a test file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        storage.storeFile(fileId, fileContent)
        
        // Generate an expired public URL
        val publicUrl = urlGenerator.generatePublicUrl(fileId, 0) // Expire immediately
        val publicId = publicUrl.substringAfterLast("/")
        
        // Test accessing the file via expired URL
        val response = client.get("/$publicId")
        
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertNull(response.headers[HttpHeaders.CacheControl])
    }
    
    @Test
    fun testPublicRouteWithInvalidURL() = testApplication {
        // Setup routing
        application {
            routing {
                publicRoutes(urlGenerator, storage, temporaryStorage)
            }
        }
        
        // Test accessing with invalid public ID
        val response = client.get("/invalid-id")
        
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun testPublicHealthEndpoint() = testApplication {
        application {
            configurePublicServer(urlGenerator, storage, temporaryStorage)
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }

    @Test
    fun testPublicRouteWithTemporaryPublicUrl() = testApplication {
        application {
            routing {
                publicRoutes(urlGenerator, storage, temporaryStorage)
            }
        }

        val tempInfo = temporaryStorage.storeTemporaryFromStream(
            "tmp-content".byteInputStream(),
            maxUploadBytes = 1024,
            ttlSeconds = 300
        )
        val publicUrl = urlGenerator.generateTemporaryPublicUrl(tempInfo.tempFileId, 5).url
        val publicId = publicUrl.substringAfterLast("/")

        val response = client.get("/$publicId")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("tmp-content", response.bodyAsText())
    }
}
