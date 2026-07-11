package dev.sdui.kmp.sample.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

/**
 * Database bootstrap for the sample server.
 *
 * Reads `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD` from the process environment and creates a
 * pooled HikariCP [DataSource] wired into Exposed. If `JDBC_URL` is unset (the typical
 * `./gradlew :samples:sample-server:run` case) the server falls back to an in-memory H2
 * database so the demo stays runnable without Postgres or Docker. A one-line warning is
 * printed in fallback mode so it cannot be missed in the console.
 *
 * The schema is created via [SchemaUtils.createMissingTablesAndColumns] on first connect —
 * the docker-compose `flyway` service applies the same DDL via `V1__initial.sql` for the
 * production-shaped path. Both keep the table set in lockstep.
 */
public object Db {
    private val logger = LoggerFactory.getLogger(Db::class.java)

    @Volatile
    private var dataSource: DataSource? = null

    @Volatile
    private var inMemoryFallback: Boolean = false

    /**
     * Whether the server is using the in-memory H2 fallback (true) or a real JDBC URL (false).
     * Surfaced for `/readiness` so it can include the mode in its response payload.
     */
    public val isFallback: Boolean get() = inMemoryFallback

    /**
     * Whether the database has been connected. `/readiness` consults this to decide whether
     * to run a connectivity probe.
     */
    public val isConnected: Boolean get() = dataSource != null

    /**
     * Hand the live transport its own `DataSource` handle so it can open a dedicated
     * `LISTEN`-only connection. Throws if [connect] has not been called yet — callers should
     * gate on [isConnected] first.
     */
    public fun dataSourceForLiveBackend(): DataSource =
        dataSource ?: error("Db.connect() must run before dataSourceForLiveBackend()")

    /**
     * Connect to the configured database. Idempotent — calling twice is a no-op.
     *
     * @param overrideDataSource test hook: if non-null, that DataSource is used verbatim and
     *   the env-var pathway is bypassed entirely. Production callers always pass `null`.
     */
    public fun connect(overrideDataSource: DataSource? = null) {
        if (dataSource != null) return

        val ds: DataSource = overrideDataSource ?: buildFromEnv()
        dataSource = ds
        Database.connect(ds)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Sessions,
                IdempotencyKeys,
                OptimisticConflicts,
            )
        }
    }

    /** Reset state. Used by tests that need a clean per-test DB. */
    internal fun resetForTesting() {
        (dataSource as? HikariDataSource)?.close()
        dataSource = null
        inMemoryFallback = false
    }

    /**
     * Run a trivial connectivity probe. Used by `/readiness`. Returns null on success or a
     * short error string on failure — never throws.
     */
    public fun probe(): String? {
        val ds = dataSource ?: return "datasource not initialised"
        return runProbe(ds)
    }

    private fun runProbe(ds: DataSource): String? = try {
        ds.connection.use { conn -> probeConnection(conn) }
    } catch (t: Throwable) {
        "probe failed: ${t.message ?: t.javaClass.simpleName}"
    }

    private fun probeConnection(conn: Connection): String? =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 1").use { rs ->
                if (rs.next()) null else "probe query returned no rows"
            }
        }

    private fun buildFromEnv(): DataSource {
        val jdbcUrl = System.getenv("JDBC_URL")
        return if (jdbcUrl.isNullOrBlank()) {
            inMemoryFallback = true
            // Print to both stderr and the structured logger; the stderr line is impossible
            // to miss when running the sample locally, the logger line is what gets shipped.
            val warning = "[sample-server] JDBC_URL not set — using in-memory H2 database. " +
                "Sessions and idempotency keys are wiped on restart. Do NOT use this in production."
            System.err.println(warning)
            logger.warn(warning)
            buildH2DataSource()
        } else {
            buildPostgresDataSource(jdbcUrl)
        }
    }

    private fun buildH2DataSource(): DataSource {
        val cfg = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:sdui-sample;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            driverClassName = "org.h2.Driver"
            maximumPoolSize = H2_POOL_SIZE
            isAutoCommit = false
            poolName = "sdui-sample-h2"
        }
        return HikariDataSource(cfg)
    }

    private fun buildPostgresDataSource(jdbcUrl: String): DataSource {
        val cfg = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = System.getenv("JDBC_USER") ?: "sdui"
            password = System.getenv("JDBC_PASSWORD").orEmpty()
            maximumPoolSize = System.getenv("JDBC_MAX_POOL_SIZE")?.toIntOrNull() ?: PG_POOL_SIZE
            isAutoCommit = false
            poolName = "sdui-sample-pg"
        }
        return HikariDataSource(cfg)
    }

    private const val H2_POOL_SIZE: Int = 4
    private const val PG_POOL_SIZE: Int = 10
}
