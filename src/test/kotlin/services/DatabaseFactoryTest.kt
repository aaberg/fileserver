package net.aabergs.services

import net.aabergs.services.database.DatabaseFactory
import net.aabergs.services.database.JdbcService
import org.junit.*
import org.junit.Assert.*

class DatabaseFactoryTest {
    
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
}