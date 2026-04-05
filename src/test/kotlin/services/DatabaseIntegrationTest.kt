package net.aabergs.services

import net.aabergs.services.database.DatabaseFactory
import org.junit.*
import org.junit.Assert.*
import java.nio.file.Files
import java.nio.file.Path

class DatabaseIntegrationTest {
    private lateinit var urlGenerator: UrlGenerator
    private lateinit var testDbPath: Path
    private val baseUrl = "http://localhost:9000"
    
    @Before
    fun setup() {
        // Use SQLite with an isolated temporary database file
        testDbPath = Files.createTempFile("fileserver-integration-", ".db")
        System.setProperty("DB_TYPE", "sqlite")
        System.setProperty("DB_URL", "jdbc:sqlite:${testDbPath.toAbsolutePath()}")
        
        val databaseService = DatabaseFactory.createDatabaseService()
        urlGenerator = UrlGenerator(baseUrl, databaseService)
    }
    
    @After
    fun teardown() {
        urlGenerator.close()
        // Clear environment properties
        System.clearProperty("DB_TYPE")
        System.clearProperty("DB_URL")
        Files.deleteIfExists(testDbPath)
    }
    
    @Test
    fun testDatabasePersistence() {
        // Test URL generation
        val fileId = "test-file-123"
        val publicUrl = urlGenerator.generatePublicUrl(fileId, 60)
        println("Generated public URL: $publicUrl")
        
        // Test URL validation
        val publicId = publicUrl.substringAfterLast("/")
        println("Public ID: $publicId")
        assertTrue(urlGenerator.isPublicUrlValid(publicId))
        assertEquals(fileId, urlGenerator.getFileIdForPublicId(publicId))
        
        // Test expiration
        val expiredUrl = urlGenerator.generatePublicUrl("expired-file", 0)
        val expiredId = expiredUrl.substringAfterLast("/")
        assertFalse(urlGenerator.isPublicUrlValid(expiredId))
        
        // Test cleanup
        urlGenerator.cleanupExpired()
        
        // Verify the valid URL still works after cleanup
        assertTrue(urlGenerator.isPublicUrlValid(publicId))
    }
    
    @Test
    fun testMultipleUrls() {
        // Generate multiple URLs
        val urls = mutableListOf<String>()
        for (i in 1..5) {
            val fileId = "test-file-$i"
            val publicUrl = urlGenerator.generatePublicUrl(fileId, 60)
            urls.add(publicUrl)
        }
        
        // Verify all URLs are valid
        for (publicUrl in urls) {
            val publicId = publicUrl.substringAfterLast("/")
            assertTrue("URL should be valid: $publicUrl", urlGenerator.isPublicUrlValid(publicId))
        }
    }
}
