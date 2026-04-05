package net.aabergs.services.database

/**
 * Configuration for JDBC database service
 * 
 * @param dbUrl JDBC connection URL
 * @param username Database username (optional for SQLite)
 * @param password Database password (optional for SQLite)
 * @param driverClass JDBC driver class name (optional, auto-detected for most databases)
 * @param tableSchema SQL statement to create the required table
 */
data class JdbcConfig(
    val dbUrl: String,
    val username: String? = null,
    val password: String? = null,
    val driverClass: String? = null,
    val tableSchema: String
)
