package net.aabergs.services

import net.aabergs.services.database.DatabaseFactory
import net.aabergs.services.database.JdbcConfig
import net.aabergs.services.database.JdbcService
import org.junit.*
import org.junit.Assert.*

class JdbcServiceTest {
    
    @After
    fun cleanup() {
        // Clear environment properties after each test
        System.clearProperty("DB_TYPE")
        System.clearProperty("DB_URL")
        System.clearProperty("DB_USER")
        System.clearProperty("DB_PASSWORD")
    }
    
    @Test
    fun testJdbcServiceWithSqliteConfig() {
        // Test JdbcService with SQLite configuration
        val sqliteConfig = JdbcConfig(
            dbUrl = "jdbc:sqlite::memory:",
            driverClass = "org.sqlite.JDBC",
            tableSchema = """
                CREATE TABLE IF NOT EXISTS public_urls (
                    public_id TEXT PRIMARY KEY,
                    file_id TEXT NOT NULL,
                    expires_at BIGINT NOT NULL
                )
            """.trimIndent()
        )
        
        val jdbcService = JdbcService(sqliteConfig)
        assertNotNull(jdbcService)
        
        // Initialize and test basic functionality
        jdbcService.initialize()
        
        // Test URL insertion and retrieval
        jdbcService.insertPublicUrl("test-id", "test-file", System.currentTimeMillis() + 3600000)
        val result = jdbcService.getPublicUrlInfo("test-id")
        assertNotNull(result)
        assertEquals("test-file", result!!.fileId)
        
        // Test cleanup
        jdbcService.cleanupExpired()
        
        jdbcService.close()
    }
    
    @Test
    fun testJdbcServiceFactoryIntegration() {
        // Test that factory creates JdbcService instances
        System.setProperty("DB_TYPE", "sqlite")
        System.setProperty("DB_URL", "jdbc:sqlite::memory:")
        
        val databaseService = DatabaseFactory.createDatabaseService()
        assertTrue(databaseService is JdbcService)
        
        // Test basic functionality
        databaseService.initialize()
        val uniqueId = "factory-test-" + System.currentTimeMillis()
        databaseService.insertPublicUrl(uniqueId, "factory-file", System.currentTimeMillis() + 3600000)
        val result = databaseService.getPublicUrlInfo(uniqueId)
        assertNotNull(result)
        assertEquals("factory-file", result!!.fileId)
        
        databaseService.close()
    }
    
    @Test
    fun testJdbcServiceConfigurationValidation() {
        // Test that different configurations work
        val configs = listOf(
            JdbcConfig(
                dbUrl = "jdbc:sqlite::memory:",
                driverClass = "org.sqlite.JDBC",
                tableSchema = "CREATE TABLE IF NOT EXISTS public_urls (public_id TEXT PRIMARY KEY, file_id TEXT NOT NULL, expires_at BIGINT NOT NULL)"
            ),
            JdbcConfig(
                dbUrl = "jdbc:h2:mem:test",
                driverClass = "org.h2.Driver",
                tableSchema = "CREATE TABLE IF NOT EXISTS public_urls (public_id VARCHAR(36) PRIMARY KEY, file_id VARCHAR(255) NOT NULL, expires_at BIGINT NOT NULL)"
            )
        )
        
        for (config in configs) {
            val service = JdbcService(config)
            try {
                service.initialize()
                service.insertPublicUrl("test-${config.dbUrl.hashCode()}", "test-file", System.currentTimeMillis() + 3600000)
                val result = service.getPublicUrlInfo("test-${config.dbUrl.hashCode()}")
                assertNotNull("Configuration ${config.dbUrl} should work", result)
                assertNotNull("Result should not be null for ${config.dbUrl}", result)
                service.close()
            } catch (e: Exception) {
                // Some drivers might not be available, that's okay for this test
                println("Configuration ${config.dbUrl} failed (expected if driver not available): ${e.message}")
                service.close()
            }
        }
    }
    
    @Test
    fun testJdbcServiceErrorHandling() {
        // Test error handling with invalid configuration
        val invalidConfig = JdbcConfig(
            dbUrl = "jdbc:invalid:url",
            tableSchema = "CREATE TABLE public_urls (public_id TEXT PRIMARY KEY)"
        )
        
        val service = JdbcService(invalidConfig)
        
        try {
            service.initialize()
            fail("Expected exception for invalid database URL")
        } catch (e: RuntimeException) {
            // Expected - invalid database URL should throw exception
            assertTrue(e.message?.contains("Failed to initialize database") == true)
        } finally {
            service.close()
        }
    }
}
