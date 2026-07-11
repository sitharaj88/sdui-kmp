package dev.sdui.kmp.studio.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Database bootstrap for the Studio backend.
 *
 * Reads `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD` from the process environment and creates a
 * pooled HikariCP [DataSource] wired into Exposed. If `JDBC_URL` is unset (the typical
 * `./gradlew :studio-server:run` case) the server falls back to an in-memory H2 database so
 * the demo stays runnable without Postgres or Docker.
 *
 * Schema management is split by backend, so production never depends on runtime auto-DDL:
 *  - **Postgres (production):** the schema is owned entirely by the Flyway migrations under
 *    `db/migrations/V*.sql`, applied out-of-process before the server boots (the docker-compose
 *    `flyway` service, mirroring the sample-server). [connect] deliberately does NOT run
 *    `SchemaUtils` against Postgres — uncontrolled runtime `ALTER`s have no ordering, review, or
 *    rollback and drift from the reviewed migration set.
 *  - **H2 (dev/`./gradlew :studio-server:run`, and the test suite):** there is no Flyway runner,
 *    so [connect] creates the schema via [SchemaUtils.createMissingTablesAndColumns] from
 *    [StudioTables]. The migration parity test asserts the Flyway set stays in lockstep with
 *    that same [StudioTables] list, so the two paths cannot silently diverge.
 */
public object StudioDatabase {
    private val logger = LoggerFactory.getLogger(StudioDatabase::class.java)

    @Volatile
    private var dataSource: DataSource? = null

    @Volatile
    private var database: Database? = null

    @Volatile
    private var inMemoryFallback: Boolean = false

    /** Whether the server is using the in-memory H2 fallback (true) or a real JDBC URL. */
    public val isFallback: Boolean get() = inMemoryFallback

    /** Whether the database has been connected. `/readiness` consults this before probing. */
    public val isConnected: Boolean get() = dataSource != null

    /** Values of `SDUI_ENV` (case-insensitive) that permit the ephemeral in-memory H2 fallback. */
    public val DEV_ENVS: Set<String> = setOf("dev", "development", "local", "test")

    /**
     * Connect to the configured database. Idempotent — calling twice is a no-op.
     *
     * @param overrideDataSource test hook: if non-null, that DataSource is used verbatim and
     *   the env-var pathway is bypassed entirely. Production callers always pass `null`.
     */
    @Suppress("SpreadOperator") // SchemaUtils.createMissingTablesAndColumns is vararg-only.
    public fun connect(overrideDataSource: DataSource? = null) {
        if (dataSource != null) return
        val ds: DataSource = overrideDataSource ?: buildFromEnv()
        dataSource = ds
        database = Database.connect(ds)

        if (isH2(ds)) {
            // H2 dev/test fallback: no Flyway runner is wired here, so create the schema at
            // runtime from the single-source-of-truth [StudioTables] list.
            transaction {
                SchemaUtils.createMissingTablesAndColumns(*StudioTables.toTypedArray())
            }
        } else {
            // Production Postgres: the schema is owned by the reviewed Flyway migrations under
            // db/migrations/, applied out-of-process before boot. Running SchemaUtils here would
            // reintroduce unordered, un-reviewed, non-rollbackable auto-DDL and schema drift.
            logger.info(
                "Postgres detected — schema is owned by Flyway (db/migrations/V*.sql); " +
                    "skipping runtime auto-DDL. Ensure migrations are applied before startup.",
            )
        }
        // M-S7: seed permissions / roles + migrate legacy editor_accounts.role into editor_roles.
        // Idempotent — safe to run on every connect().
        dev.sdui.kmp.studio.server.rbac.RbacBootstrap.bootstrap()
    }

    /**
     * The pooled [DataSource] backing this connection, for components that need their own JDBC
     * connection outside Exposed — notably a [dev.sdui.kmp.transport.live.PostgresLivePublisher]
     * running `LISTEN sdui_live` on a dedicated connection. Callers MUST gate on [isConnected]
     * (and typically [isFallback], since H2 cannot do `LISTEN`/`NOTIFY`) first; throws if
     * [connect] has not run.
     */
    public fun dataSourceForLiveBackend(): DataSource =
        dataSource ?: error("StudioDatabase.connect() must run before dataSourceForLiveBackend()")

    /**
     * Trivial connectivity probe for `/readiness`. Returns null on success or a short error
     * string on failure — never throws, so a Postgres outage surfaces as a 503 rather than a
     * crash. Mirrors the sample-server's `Db.probe()`.
     */
    public fun probe(): String? {
        val ds = dataSource ?: return "datasource not initialised"
        return runProbe(ds)
    }

    private fun runProbe(ds: DataSource): String? = try {
        ds.connection.use { conn -> probeConnection(conn) }
    } catch (
        @Suppress("TooGenericExceptionCaught") t: Throwable,
    ) {
        "probe failed: ${t.message ?: t.javaClass.simpleName}"
    }

    private fun probeConnection(conn: java.sql.Connection): String? =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 1").use { rs ->
                if (rs.next()) null else "probe query returned no rows"
            }
        }

    /**
     * Refuse to boot on the ephemeral H2 fallback outside an explicit dev environment.
     *
     * The in-memory H2 fallback wipes drafts, versions, RBAC, and the audit log on every
     * restart — silently acceptable for `./gradlew :studio-server:run`, catastrophic in
     * production. This mirrors the secure-by-default policy of
     * [dev.sdui.kmp.studio.server.auth.StudioJwt.resolveSecret] and
     * [dev.sdui.kmp.studio.server.experiments.AssignRouteConfig.resolveToken]: extracted as a
     * pure function of [sduiEnv] so the policy is unit-testable without mutating process env.
     *
     * @throws IllegalStateException if [sduiEnv] does not name a dev environment (see [DEV_ENVS]).
     */
    public fun requireH2FallbackAllowed(sduiEnv: String?) {
        check(sduiEnv?.lowercase() in DEV_ENVS) {
            "JDBC_URL is not set. Refusing to start: the in-memory H2 fallback wipes drafts, " +
                "versions, RBAC, and the audit log on every restart. Set JDBC_URL to a real " +
                "Postgres URL, or set SDUI_ENV to one of $DEV_ENVS for local development only."
        }
    }

    /**
     * Close the pooled DataSource and unregister from Exposed. Idempotent — safe to call when
     * already shut down. Wired to the host's `ApplicationStopping` hook so a rolling deploy
     * drains the connection pool instead of leaking it. Production shutdown does not depend on
     * a test-only seam, so this is public and distinct from [resetForTesting].
     */
    public fun shutdown() {
        // Unregister from Exposed BEFORE closing the pool so any in-flight transaction holding a
        // reference to this Database doesn't try to use a closed pool.
        database?.let { runCatching { TransactionManager.closeAndUnregister(it) } }
        (dataSource as? HikariDataSource)?.close()
        database = null
        dataSource = null
        inMemoryFallback = false
    }

    /** Reset state. Used by tests that need a clean per-test DB. Same teardown as [shutdown]. */
    internal fun resetForTesting(): Unit = shutdown()

    /**
     * Whether [ds] is backed by H2 (the dev/test fallback) rather than Postgres. Detected from
     * JDBC metadata rather than the [inMemoryFallback] flag so a test that injects its own H2
     * `overrideDataSource` is still recognised as the auto-DDL path. Never throws: an unreachable
     * datasource here is treated as "not H2", so production keeps its Flyway-only contract and the
     * real connectivity failure surfaces later via [probe]/`/readiness`.
     */
    private fun isH2(ds: DataSource): Boolean = try {
        ds.connection.use { it.metaData.databaseProductName.equals("H2", ignoreCase = true) }
    } catch (
        @Suppress("TooGenericExceptionCaught") t: Throwable,
    ) {
        logger.warn("Could not read JDBC product name; assuming Postgres (Flyway-managed schema): {}", t.message)
        false
    }

    private fun buildFromEnv(): DataSource {
        val jdbcUrl = System.getenv("JDBC_URL")
        return if (jdbcUrl.isNullOrBlank()) {
            requireH2FallbackAllowed(System.getenv("SDUI_ENV"))
            inMemoryFallback = true
            val warning = "[studio-server] JDBC_URL not set — using in-memory H2 database. " +
                "Drafts, versions, and audit history are wiped on restart. Do NOT use this in production."
            System.err.println(warning)
            logger.warn(warning)
            buildH2DataSource()
        } else {
            buildPostgresDataSource(jdbcUrl)
        }
    }

    private fun buildH2DataSource(): DataSource {
        val cfg = HikariConfig().apply {
            // Random suffix so concurrent tests don't share state across testApplication runs.
            jdbcUrl = "jdbc:h2:mem:sdui-studio-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            driverClassName = "org.h2.Driver"
            maximumPoolSize = H2_POOL_SIZE
            isAutoCommit = false
            poolName = "sdui-studio-h2"
        }
        return HikariDataSource(cfg)
    }

    private fun buildPostgresDataSource(jdbcUrl: String): DataSource {
        val cfg = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = System.getenv("JDBC_USER") ?: "sdui_studio"
            password = System.getenv("JDBC_PASSWORD").orEmpty()
            maximumPoolSize = System.getenv("JDBC_MAX_POOL_SIZE")?.toIntOrNull() ?: PG_POOL_SIZE
            isAutoCommit = false
            poolName = "sdui-studio-pg"
        }
        return HikariDataSource(cfg)
    }

    private const val H2_POOL_SIZE: Int = 4
    private const val PG_POOL_SIZE: Int = 10
}
