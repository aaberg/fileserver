package net.aabergs.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.routing.*
import org.junit.*
import org.junit.Assert.*
import net.aabergs.services.FileStorage
import net.aabergs.services.UrlGenerator
import net.aabergs.services.database.DatabaseFactory
import java.io.File
import java.nio.file.Files

class PrivateRoutesTest {
    private lateinit var storage: FileStorage
    private lateinit var urlGenerator: UrlGenerator
    private val testDir = Files.createTempDirectory("fileserver-test").toString()
    private val baseUrl = "http://localhost:9000"
    
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
            routing {
                privateRoutes(storage, urlGenerator)
            }
        }
        
        // Test uploading a file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        
        val response = client.put("/file/$fileId") {
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
            routing {
                privateRoutes(storage, urlGenerator)
            }
        }
        
        // Store a test file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        storage.storeFile(fileId, fileContent)
        
        // Test getting the file
        val response = client.get("/file/$fileId")
        
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, World!", response.bodyAsText())
    }
    
    @Test
    fun testGetNonExistentFile() = testApplication {
        // Setup routing
        application {
            routing {
                privateRoutes(storage, urlGenerator)
            }
        }
        
        // Test getting a non-existent file
        val response = client.get("/file/non-existent")
        
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
    
    @Test
    fun testDeleteFile() = testApplication {
        // Setup routing
        application {
            routing {
                privateRoutes(storage, urlGenerator)
            }
        }
        
        // Store a test file
        val fileId = "test-file"
        val fileContent = "Hello, World!".toByteArray()
        storage.storeFile(fileId, fileContent)
        
        // Test deleting the file
        val response = client.delete("/file/$fileId")
        
        assertEquals(HttpStatusCode.OK, response.status)
        
        // Verify file was deleted
        val deletedContent = storage.getFile(fileId)
        assertNull(deletedContent)
    }
    
    @Test
    fun testGeneratePublicURL() = testApplication {
        // Setup routing
        application {
            routing {
                privateRoutes(storage, urlGenerator)
            }
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
}