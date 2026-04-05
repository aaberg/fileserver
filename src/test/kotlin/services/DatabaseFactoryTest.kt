package net.aabergs.services

import net.aabergs.services.database.DatabaseFactory
import net.aabergs.services.database.JdbcService
import org.junit.*
import org.junit.Assert.*

class DatabaseFactoryTest {
    @After
    fun cleanup() {
        System.clearProperty("DB_TYPE")
        System.clearProperty("DB_URL")
        System.clearProperty("DB_USER")
        System.clearProperty("DB_PASSWORD")
    }

    
    @Test
    fun testDefaultSqliteDatabase() {
        // Test default configuration (should be JdbcService with SQLite config)
        val databaseService = DatabaseFactory.createDatabaseService()
        assertTrue(databaseService is JdbcService)
        databaseService.close()
    }
    
    @Test
    fun testDatabaseServiceCreation() {
        // Just test that we can create a database service without errors
        val databaseService = DatabaseFactory.createDatabaseService()
        assertNotNull(databaseService)
        databaseService.close()
    }

    @Test
    fun testSystemPropertyConfigurationTakesPrecedence() {
        System.setProperty("DB_TYPE", "postgres")

        try {
            DatabaseFactory.createDatabaseService()
            fail("Expected missing postgres credentials to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("DB_USER and DB_PASSWORD") == true)
        }
    }
}
