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
import java.io.File
import java.nio.file.Files

class FileStorageErrorHandlingTest {
    private lateinit var storage: FileStorage
    private lateinit var urlGenerator: UrlGenerator
    private val privateApiToken = "test-private-token"
    private val testDir = Files.createTempDirectory("fileserver-test").toString()
    private val baseUrl = "http://localhost:9000"
    
    @Before
    fun setup() {
        storage = FileStorage(testDir)
        // Use SQLite for tests with a temporary database
        System.setProperty("DB_TYPE", "sqlite")
        System.setProperty("DB_URL", "jdbc:sqlite::memory:")
        
        val databaseService = net.aabergs.services.database.DatabaseFactory.createDatabaseService()
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
    fun testFileStorageErrorReturns500() = testApplication {
        // Setup routing
        application {
            routing {
                privateRoutes(storage, urlGenerator, privateApiToken)
            }
        }
        
        // Make the storage directory read-only to trigger an error
        val storageDir = File(testDir)
        storageDir.setReadable(false)
        storageDir.setWritable(false)
        
        try {
            // Try to upload a file - should fail with 500
            val response = client.put("/file/test-error") {
                header(HttpHeaders.Authorization, "Bearer $privateApiToken")
                setBody("test content".toByteArray())
            }
            
            // Should return 500 Internal Server Error
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        } finally {
            // Restore permissions
            storageDir.setReadable(true)
            storageDir.setWritable(true)
        }
    }
    
    @Test
    fun testFileStorageSuccessReturns200() = testApplication {
        // Setup routing
        application {
            routing {
                privateRoutes(storage, urlGenerator, privateApiToken)
            }
        }
        
        // Upload a file - should succeed with 200
        val response = client.put("/file/test-success") {
            header(HttpHeaders.Authorization, "Bearer $privateApiToken")
            setBody("test content".toByteArray())
        }
        
        // Should return 200 OK
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
