package net.aabergs.services.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.aabergs.services.PublicUrlInfo

/**
 * Common JDBC implementation for database services
 * 
 * This class handles all database operations using a configurable JDBC connection.
 * Database-specific configurations (URL, credentials, schema) are provided via JdbcConfig.
 * 
 * @param config Configuration for the JDBC connection and database schema
 */
class JdbcService(private val config: JdbcConfig) : DatabaseService {
    private lateinit var dataSource: HikariDataSource

    override fun initialize() {
        try {
            // Load JDBC driver if specified
            config.driverClass?.let { driver ->
                Class.forName(driver)
            }

            // Create HikariCP configuration
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = config.dbUrl
                config.username?.let { username = it }
                config.password?.let { password = it }
                maximumPoolSize = 10
                connectionTimeout = 30000
                idleTimeout = 600000
                maxLifetime = 1800000
            }

            dataSource = HikariDataSource(hikariConfig)

            // Create table if it doesn't exist
            createTableIfNotExists()
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize database: ${e.message}", e)
        }
    }

    private fun createTableIfNotExists() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(config.tableSchema)
            }
        }
    }

    override fun insertPublicUrl(publicId: String, fileId: String, expiresAt: Long) {
        val sql = "INSERT INTO public_urls (public_id, file_id, expires_at) VALUES (?, ?, ?)"
        
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, publicId)
                stmt.setString(2, fileId)
                stmt.setLong(3, expiresAt)
                stmt.executeUpdate()
            }
        }
    }

    override fun getPublicUrlInfo(publicId: String): PublicUrlInfo? {
        val sql = "SELECT file_id, expires_at FROM public_urls WHERE public_id = ?"
        
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, publicId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return PublicUrlInfo(rs.getString("file_id"), rs.getLong("expires_at"))
                    }
                }
            }
        }
        return null
    }

    override fun cleanupExpired() {
        val sql = "DELETE FROM public_urls WHERE expires_at < ?"
        val now = System.currentTimeMillis()
        
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, now)
                stmt.executeUpdate()
            }
        }
    }

    override fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }
}
