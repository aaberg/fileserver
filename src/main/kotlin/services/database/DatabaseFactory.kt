package net.aabergs.services.database

class DatabaseFactory {
    companion object {
        private fun configValue(key: String): String? {
            return System.getProperty(key) ?: System.getenv(key)
        }

        fun createDatabaseService(): DatabaseService {
            val dbType = configValue("DB_TYPE") ?: "sqlite"
            val dbUrl = configValue("DB_URL") ?: getDefaultDbUrl(dbType)
            val dbUser = configValue("DB_USER")
            val dbPassword = configValue("DB_PASSWORD")

            val config = when (dbType.lowercase()) {
                "postgres", "postgresql" -> {
                    if (dbUser == null || dbPassword == null) {
                        throw IllegalStateException("DB_USER and DB_PASSWORD must be set for PostgreSQL")
                    }
                    createPostgresConfig(dbUrl, dbUser, dbPassword)
                }
                "sqlite", "default" -> {
                    createSqliteConfig(dbUrl)
                }
                else -> {
                    throw IllegalStateException("Unsupported database type: $dbType. Use 'sqlite' or 'postgres'")
                }
            }

            return JdbcService(config)
        }

        private fun createSqliteConfig(dbUrl: String): JdbcConfig {
            return JdbcConfig(
                dbUrl = dbUrl,
                driverClass = "org.sqlite.JDBC",
                tableSchema = """
                    CREATE TABLE IF NOT EXISTS public_urls (
                        public_id TEXT PRIMARY KEY,
                        file_id TEXT NOT NULL,
                        expires_at BIGINT NOT NULL
                    )
                """.trimIndent()
            )
        }

        private fun createPostgresConfig(dbUrl: String, username: String, password: String): JdbcConfig {
            return JdbcConfig(
                dbUrl = dbUrl,
                username = username,
                password = password,
                tableSchema = """
                    CREATE TABLE IF NOT EXISTS public_urls (
                        public_id VARCHAR(36) PRIMARY KEY,
                        file_id VARCHAR(255) NOT NULL,
                        expires_at BIGINT NOT NULL
                    )
                """.trimIndent()
            )
        }

        private fun getDefaultDbUrl(dbType: String): String {
            return when (dbType.lowercase()) {
                "sqlite", "default" -> "jdbc:sqlite:fileserver.db"
                "postgres", "postgresql" -> "jdbc:postgresql://localhost:5432/fileserver"
                else -> "jdbc:sqlite:fileserver.db"
            }
        }
    }
}
