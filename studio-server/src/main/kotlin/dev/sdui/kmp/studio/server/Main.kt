package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.db.StudioDatabase
import dev.sdui.kmp.transport.live.InProcessLiveBus
import dev.sdui.kmp.transport.live.LiveBus
import dev.sdui.kmp.transport.live.PostgresLivePublisher
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Studio backend entrypoint. Defaults to port 8081 so it can run side-by-side with the
 * sample-server (8080) during development. Override with the `PORT` environment variable.
 *
 * Run with `./gradlew :studio-server:run`. With no `JDBC_URL` the server boots against an
 * in-memory H2 database **only** when `SDUI_ENV` names a dev environment (`dev`/`development`/
 * `local`/`test`); in any other environment it refuses to start rather than silently wiping
 * drafts, versions, RBAC, and audit history on restart. See
 * [dev.sdui.kmp.studio.server.db.StudioDatabase] for the full env-var contract.
 *
 * Live backend selection: set `LIVE_BACKEND=postgres` (and a real `JDBC_URL`) to fan publishes
 * out across every studio/fan-out instance sharing the database via Postgres `LISTEN`/`NOTIFY`.
 * With anything else — or on the H2 dev fallback, which cannot do `LISTEN`/`NOTIFY` — the
 * process uses a single-JVM [InProcessLiveBus].
 */
public fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    // Connect the database before resolving the live bus so a Postgres-backed bus can borrow
    // the pooled DataSource for its dedicated LISTEN connection.
    StudioDatabase.connect()
    val bus = resolveStudioLiveBus()
    embeddedServer(Netty, port = port, host = "0.0.0.0") { studioModule(bus = bus) }.start(wait = true)
}

/**
 * Choose the process-wide [LiveBus]: a [PostgresLivePublisher] when the operator opted in via
 * `LIVE_BACKEND=postgres` and a real (non-fallback) Postgres data source is connected;
 * otherwise a single-JVM [InProcessLiveBus].
 */
private fun resolveStudioLiveBus(): LiveBus {
    val backend = System.getenv(LIVE_BACKEND_ENV)?.lowercase()?.takeIf { it.isNotBlank() }
    if (backend != LIVE_BACKEND_POSTGRES) return InProcessLiveBus()
    if (!StudioDatabase.isConnected || StudioDatabase.isFallback) return InProcessLiveBus()
    return PostgresLivePublisher(dataSource = StudioDatabase.dataSourceForLiveBackend())
}

private const val DEFAULT_PORT: Int = 8081
private const val LIVE_BACKEND_ENV: String = "LIVE_BACKEND"
private const val LIVE_BACKEND_POSTGRES: String = "postgres"
