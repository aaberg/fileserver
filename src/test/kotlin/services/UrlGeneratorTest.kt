package net.aabergs.services

import net.aabergs.services.database.DatabaseFactory
import org.junit.*
import org.junit.Assert.*
import java.util.regex.Pattern

class UrlGeneratorTest {
    private lateinit var urlGenerator: UrlGenerator
    private val baseUrl = "http://localhost:9000"
    
    @Before
    fun setup() {
        // Use SQLite for tests with a temporary database
        System.setProperty("DB_TYPE", "sqlite")
        System.setProperty("DB_URL", "jdbc:sqlite::memory:")
        
        val databaseService = DatabaseFactory.createDatabaseService()
        urlGenerator = UrlGenerator(baseUrl, databaseService)
    }
    
    @After
    fun teardown() {
        urlGenerator.close()
        // Clear environment properties
        System.clearProperty("DB_TYPE")
        System.clearProperty("DB_URL")
    }
    
    @Test
    fun testGeneratePublicURL() {
        val fileId = "test-file"
        val durationMinutes = 60L
        
        val publicUrl = urlGenerator.generatePublicUrl(fileId, durationMinutes)
        
        // Verify URL format
        assertTrue(publicUrl.startsWith(baseUrl + "/"))
        
        // Verify URL contains UUID
        val uuidPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val pattern = Pattern.compile("$baseUrl/($uuidPattern)")
        val matcher = pattern.matcher(publicUrl)
        assertTrue(matcher.matches())
    }
    
    @Test
    fun testPublicURLValidation() {
        val fileId = "test-file"
        val durationMinutes = 1L // 1 minute
        
        val publicUrl = urlGenerator.generatePublicUrl(fileId, durationMinutes)
        val publicId = publicUrl.substringAfterLast("/")
        
        // URL should be valid immediately
        assertTrue(urlGenerator.isPublicUrlValid(publicId))
        
        // Verify we can get the file ID back
        val retrievedFileId = urlGenerator.getFileIdForPublicId(publicId)
        assertEquals(fileId, retrievedFileId)
    }
    
    @Test
    fun testPublicURLExpiration() {
        val fileId = "test-file"
        val durationMinutes = 0L // Expire immediately
        
        val publicUrl = urlGenerator.generatePublicUrl(fileId, durationMinutes)
        val publicId = publicUrl.substringAfterLast("/")
        
        // URL should be expired immediately
        assertFalse(urlGenerator.isPublicUrlValid(publicId))
    }
    
    @Test
    fun testInvalidPublicID() {
        assertFalse(urlGenerator.isPublicUrlValid("invalid-id"))
        assertNull(urlGenerator.getFileIdForPublicId("invalid-id"))
    }
}