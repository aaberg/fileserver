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
import net.aabergs.services.TemporaryFileStorage
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseFactory
import java.io.File
import java.nio.file.Files

class PrivateRoutesTest {
    private lateinit var storage: FileStorage
    private lateinit var temporaryStorage: TemporaryFileStorage
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
            privateRoutes(storage, temporaryStorage, urlGenerator, privateApiToken, maxUploadBytes)
        }
    }
    
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
            contentType(ContentType.Image.PNG)
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
        assertEquals("application/octet-stream", response.headers[HttpHeaders.ContentType])
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
            contentType(ContentType.Image.PNG)
            setBody(content)
        }
        val getResponse = client.get("/file/$fileId") {
            withAuth()
        }

        assertEquals(HttpStatusCode.OK, putResponse.status)
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals("Hello with extension", getResponse.bodyAsText())
        assertEquals("image/png", getResponse.headers[HttpHeaders.ContentType])
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
            configurePrivateServer(
                urlGenerator,
                storage,
                temporaryStorage,
                privateApiToken,
                DEFAULT_MAX_UPLOAD_BYTES,
                DEFAULT_MAX_UPLOAD_BYTES,
                3600
            )
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }

    @Test
    fun testTemporaryUploadAndPromoteFlow() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val uploadResponse = client.post("/temp-file") {
            withAuth()
            contentType(ContentType.Image.PNG)
            setBody("temporary-content".toByteArray())
        }
        assertEquals(HttpStatusCode.OK, uploadResponse.status)

        val body = uploadResponse.bodyAsText()
        val tempFileId = Regex("\"tempFileId\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        assertNotNull(tempFileId)

        val promoteResponse = client.post("/temp-file/$tempFileId/promote/promoted.txt") {
            withAuth()
        }
        assertEquals(HttpStatusCode.OK, promoteResponse.status)
        assertEquals("temporary-content", storage.getFile("promoted.txt")?.toString(Charsets.UTF_8))
        assertEquals("image/png", storage.getStoredFileInfo("promoted.txt")?.contentType)
    }

    @Test
    fun testCreatePublicUrlForTemporaryFile() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val uploadResponse = client.post("/temp-file") {
            withAuth()
            setBody("temporary-content".toByteArray())
        }
        val tempFileId = Regex("\"tempFileId\":\"([^\"]+)\"").find(uploadResponse.bodyAsText())?.groupValues?.get(1)
        assertNotNull(tempFileId)

        val publicUrlResponse = client.post("/temp-file/$tempFileId/public-url") {
            withAuth()
            contentType(ContentType.Application.Json)
            setBody("{\"duration\":5}")
        }

        assertEquals(HttpStatusCode.OK, publicUrlResponse.status)
        assertTrue(publicUrlResponse.bodyAsText().contains("\"publicUrl\":"))
    }

    @Test
    fun testGetTemporaryFile() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val uploadResponse = client.post("/temp-file") {
            withAuth()
            contentType(ContentType.Image.JPEG)
            setBody("temporary-content".toByteArray())
        }
        assertEquals(HttpStatusCode.OK, uploadResponse.status)
        val tempFileId = Regex("\"tempFileId\":\"([^\"]+)\"").find(uploadResponse.bodyAsText())?.groupValues?.get(1)
        assertNotNull(tempFileId)

        val getResponse = client.get("/temp-file/$tempFileId") {
            withAuth()
        }

        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals("temporary-content", getResponse.bodyAsText())
        assertEquals("image/jpeg", getResponse.headers[HttpHeaders.ContentType])
    }

    @Test
    fun testGetMissingTemporaryFileReturns404() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val response = client.get("/temp-file/7a093fd8-74be-4648-ba8a-c17f57ec799f") {
            withAuth()
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun testGetTemporaryFileWithoutAuthReturns401() = testApplication {
        application {
            configurePrivateRoutesForTest()
        }

        val response = client.get("/temp-file/7a093fd8-74be-4648-ba8a-c17f57ec799f")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("\"error\":\"unauthorized\""))
    }
}
