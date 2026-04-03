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

class PublicRoutesTest {
    private lateinit var storage: FileStorage
    private lateinit var urlGenerator: UrlGenerator
    private val testDir = Files.createTempDirectory("fileserver-test").toString()
    private val baseUrl = "http://localhost:9000"
    
    @Before
    fun setup() {
        storage = FileStorage(testDir)
        urlGenerator = UrlGenerator(baseUrl)
    }
    
    @After
    fun cleanup() {
        File(testDir).deleteRecursively()
    }
    
    @Test
    fun testPublicRouteWithValidURL() = testApplication {
        // Setup routing
        application {
            routing {
                publicRoutes(urlGenerator, storage)
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
    }
    
    @Test
    fun testPublicRouteWithExpiredURL() = testApplication {
        // Setup routing
        application {
            routing {
                publicRoutes(urlGenerator, storage)
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
    }
    
    @Test
    fun testPublicRouteWithInvalidURL() = testApplication {
        // Setup routing
        application {
            routing {
                publicRoutes(urlGenerator, storage)
            }
        }
        
        // Test accessing with invalid public ID
        val response = client.get("/invalid-id")
        
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}