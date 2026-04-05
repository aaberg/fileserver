package net.aabergs.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.server.routing.*
import org.junit.*
import org.junit.Assert.*
import net.aabergs.configureCommonPlugins
import net.aabergs.configurePrivateServer
import net.aabergs.services.FileStorage
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseFactory
import java.io.File
import java.nio.file.Files

class PrivateRoutesTest {
    private lateinit var storage: FileStorage
    private lateinit var urlGenerator: UrlGenerator
    private val privateApiToken = "test-private-token"
    private val testDir = Files.createTempDirectory("fileserver-test").toString()
    private val baseUrl = "http://localhost:9000"

    private fun HttpRequestBuilder.withAuth() {
        header(HttpHeaders.Authorization, "Bearer $privateApiToken")
    }

    private fun Application.configurePrivateRoutesForTest(maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES) {
        configureCommonPlugins()
        routing {
            privateRoutes(storage, urlGenerator, privateApiToken, maxUploadBytes)
        }
    }
    
    @Before
    fun setup() {
        storage = FileStorage(testDir)
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
    fun testUploadFile() = testApplication {
        // Setup routing
        application {
            configurePrivateRoutesForTest()
        }
        
        // Test uploading a file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        
        val response = client.put("/file/$fileId") {
            withAuth()
            setBody(fileContent)
        }
        
        assertEquals(HttpStatusCode.OK, response.status)
        
        // Verify file was stored
        val storedContent = storage.getFile(fileId)
        assertNotNull(storedContent)
        assertArrayEquals(fileContent, storedContent)
    }
    
    @Test
    fun testGetFile() = testApplication {
        // Setup routing
        application {
            configurePrivateRoutesForTest()
        }
        
        // Store a test file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        storage.storeFile(fileId, fileContent)
        
        // Test getting the file
        val response = client.get("/file/$fileId") {
            withAuth()
        }
        
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, World!", response.bodyAsText())
    }
    
    @Test
    fun testGetNonExistentFile() = testApplication {
        // Setup routing
        application {
            configurePrivateRoutesForTest()
        }
        
        // Test getting a non-existent file
        val response = client.get("/file/non-existent") {
            withAuth()
        }
        
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
    
    @Test
    fun testDeleteFile() = testApplication {
        // Setup routing
        application {
            configurePrivateRoutesForTest()
        }
        
        // Store a test file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        storage.storeFile(fileId, fileContent)
        
        // Test deleting the file
        val response = client.delete("/file/$fileId") {
            withAuth()
        }
        
        assertEquals(HttpStatusCode.OK, response.status)
        
        // Verify file was deleted
        val deletedContent = storage.getFile(fileId)
        assertNull(deletedContent)
    }
    
    @Test
    fun testGeneratePublicURL() = testApplication {
        // Setup routing
        application {
            configurePrivateRoutesForTest()
        }
        
        // Store a test file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        storage.storeFile(fileId, fileContent)
        
        // Test generating a public URL - test the service directly instead of HTTP
        val publicUrl = urlGenerator.generatePublicUrl(fileId, 60)
        
        assertTrue(publicUrl.contains(baseUrl))
        assertTrue(publicUrl.contains("/"))
        
        // Verify the URL is valid
        val publicId = publicUrl.substringAfterLast("/")
        assertTrue(urlGenerator.isPublicUrlValid(publicId))
        assertEquals(fileId, urlGenerator.getFileIdForPublicId(publicId))
    }

    @Test
    fun testRejectsPathTraversalFileId() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val response = client.put("/file/%2E%2E%2Fetc%2Fpasswd") {
            withAuth()
            setBody("bad".toByteArray())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"error\":\"bad_request\""))
    }

    @Test
    fun testRejectsDotAndDotDotFileIds() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val singleDotResponse = client.put("/file/.") {
            withAuth()
            setBody("bad".toByteArray())
        }
        val doubleDotResponse = client.put("/file/..") {
            withAuth()
            setBody("bad".toByteArray())
        }

        assertEquals(HttpStatusCode.BadRequest, singleDotResponse.status)
        assertEquals(HttpStatusCode.BadRequest, doubleDotResponse.status)
    }

    @Test
    fun testAllowsFileIdWithExtension() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val fileId = "report.txt"
        val content = "Hello with extension".toByteArray()

        val putResponse = client.put("/file/$fileId") {
            withAuth()
            setBody(content)
        }
        val getResponse = client.get("/file/$fileId") {
            withAuth()
        }

        assertEquals(HttpStatusCode.OK, putResponse.status)
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals("Hello with extension", getResponse.bodyAsText())
    }

    @Test
    fun testUploadTooLargeReturns413() = testApplication {
        application {
            configurePrivateRoutesForTest(maxUploadBytes = 5)
        }

        val response = client.put("/file/too-large.txt") {
            withAuth()
            setBody("123456".toByteArray())
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains("\"error\":\"payload_too_large\""))
        assertNull(storage.getFile("too-large.txt"))
    }

    @Test
    fun testMissingAuthorizationReturns401() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val response = client.get("/file/test-file")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("\"error\":\"unauthorized\""))
    }

    @Test
    fun testInvalidAuthorizationReturns401() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val response = client.get("/file/test-file") {
            header(HttpHeaders.Authorization, "Bearer wrong-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("\"error\":\"unauthorized\""))
    }

    @Test
    fun testPrivateHealthEndpointIsOpen() = testApplication {
        application {
            configurePrivateServer(urlGenerator, storage, privateApiToken, DEFAULT_MAX_UPLOAD_BYTES)
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }
}
