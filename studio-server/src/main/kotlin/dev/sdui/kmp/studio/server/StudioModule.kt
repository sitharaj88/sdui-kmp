package dev.sdui.kmp.studio.server

import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.studio.server.auth.StudioJwt
import dev.sdui.kmp.studio.server.db.EditorAccountStore
import dev.sdui.kmp.studio.server.db.EditorRole
import dev.sdui.kmp.studio.server.db.StudioDatabase
import dev.sdui.kmp.studio.server.experiments.AssignRouteConfig
import dev.sdui.kmp.studio.server.experiments.installExperimentRoutes
import dev.sdui.kmp.studio.server.routes.DraftValidator
import dev.sdui.kmp.studio.server.routes.PublishNotifier
import dev.sdui.kmp.studio.server.routes.WebSocketPublishNotifier
import dev.sdui.kmp.studio.server.routes.installAuditAdminRoutes
import dev.sdui.kmp.studio.server.routes.installEditorAuthRoutes
import dev.sdui.kmp.studio.server.routes.installScreensAdminRoutes
import dev.sdui.kmp.studio.server.routes.installVersionsAdminRoutes
import dev.sdui.kmp.transport.live.DynamicLiveBusBridge
import dev.sdui.kmp.transport.live.InProcessLiveBus
import dev.sdui.kmp.transport.live.LiveBus
import dev.sdui.kmp.transport.live.WebSocketLivePublisher
import dev.sdui.kmp.transport.live.installLiveScreensRoute
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.Closeable

/** JWT authentication provider name shared between the auth installer and route guards. */
public const val STUDIO_JWT_AUTH: String = "studio-jwt"

/** CSRF header name allowlisted by the dev CORS config (matches the auth-rs256 default). */
private const val STUDIO_CSRF_HEADER: String = "X-CSRF-Token"

/** Env values that mark a local/dev environment (mirrors [StudioJwt.DEV_ENVS] minus test). */
private val DEV_RUN_ENVS: Set<String> = setOf("dev", "development", "local")

/** Seeded dev editor credentials — created only in a dev environment so the Studio has a login. */
private const val DEV_EDITOR_EMAIL: String = "dev@studio.local"
private const val DEV_EDITOR_PASSWORD: String = "studio-dev-password"

private fun isDevEnv(): Boolean = System.getenv("SDUI_ENV")?.lowercase() in DEV_RUN_ENVS

/**
 * In a dev environment only, seed an admin editor account so the Studio web UI has a login out of
 * the box. Idempotent (no-op if the account exists) and never runs outside [DEV_RUN_ENVS], so
 * production and the test suite (SDUI_ENV=test) are unaffected.
 */
private fun Application.seedDevEditorIfDev() {
    if (!isDevEnv()) return
    runBlocking {
        if (EditorAccountStore.findByEmail(DEV_EDITOR_EMAIL) == null) {
            EditorAccountStore.create(DEV_EDITOR_EMAIL, DEV_EDITOR_PASSWORD, "Dev Editor", EditorRole.Admin)
            log.info("Seeded dev editor account '$DEV_EDITOR_EMAIL' (SDUI_ENV=dev). Do not use in production.")
        }
    }
}

/**
 * Application module for the Studio backend. Installs:
 *
 *  - The `/admin/auth` login and logout routes (unauthenticated login, JWT-guarded logout).
 *  - The `/admin/screens` admin surface (list / get / draft / publish / versions / revert / delete).
 *  - The `/admin/audit` audit-log query route.
 *  - The `/live/screens/{id}` WebSocket fan-out for hot-reloading clients on publish.
 *  - The production prelude: request-id correlation ([StudioRequestIdPlugin]), an access log
 *    ([StudioRequestLogPlugin]), a `/health` liveness ping, and a `/readiness` probe that
 *    reflects database connectivity.
 *
 * Graceful shutdown: an `ApplicationStopping` hook cancels [liveBridgeScope] (stopping the
 * bus -> WebSocket forwarders), drains the live WebSocket sessions via [livePublisher], closes
 * [bus] when it is [Closeable] (the Postgres `LISTEN` connection), and — when this module owns
 * the connection ([connectDb] is true) — closes the Hikari pool. No resource leaks on stop.
 *
 * Live publish wiring: the publish path is fully decoupled from the local WebSocket
 * registry. [notifier] publishes each freshly-committed screen onto the shared [bus]; a
 * [DynamicLiveBusBridge] wired to the same [bus] then fans that event out to the sockets
 * registered on this JVM's [livePublisher] (installed under `/live/screens/{id}`). Because
 * every studio/fan-out instance connected to the same [bus] runs its own bridge, a publish
 * on instance A reaches a subscriber on instance B — the single-JVM-only broadcast bug the
 * previous wiring had. The bridge is on-demand: a topic is bridged when its first local
 * client subscribes and torn down when the last one leaves. Tests that pass an explicit
 * recording [notifier] skip the bus entirely and just observe the publish-side call.
 *
 * @param bus cross-process live bus. Defaults to an [InProcessLiveBus] (dev/tests, single
 *   JVM); production passes a [dev.sdui.kmp.transport.live.PostgresLivePublisher] so publishes
 *   fan out across every instance sharing the database.
 * @param liveBridgeScope coroutine scope owning the per-topic bus -> WebSocket forwarders.
 *   Defaults to a dedicated [SupervisorJob]-backed scope that the shutdown hook cancels, so
 *   the bridge is drained deterministically on stop rather than relying on engine teardown.
 * @param livePublisher fan-out registry backing `/live/screens/{id}`. Defaults to the
 *   [DynamicLiveBusBridge]'s bridged publisher so subscribing a client lazily bridges its
 *   topic off [bus]. Pass an explicit instance only for a host that manages bridging itself.
 * @param notifier hook invoked after every successful publish. Defaults to a
 *   [WebSocketPublishNotifier] that publishes onto [bus]; tests pass [NoopPublishNotifier]
 *   or a recording stub.
 * @param connectDb test seam: pass `false` to skip [StudioDatabase.connect]. Production paths
 *   always use `true`.
 * @param validator overrideable for tests that need a captured baseline; production code uses
 *   the classpath baseline.
 * @param assignConfig service-token + rate-limit policy for the `/screens/{id}/assign` route.
 *   Defaults to [AssignRouteConfig.fromEnv]; tests pass an explicit config to exercise the gate.
 */
public fun Application.studioModule(
    bus: LiveBus = InProcessLiveBus(),
    liveBridgeScope: CoroutineScope = newLiveBridgeScope(),
    livePublisher: WebSocketLivePublisher = DynamicLiveBusBridge(bus, liveBridgeScope).publisher,
    notifier: PublishNotifier = WebSocketPublishNotifier(bus),
    connectDb: Boolean = true,
    validator: DraftValidator = DraftValidator.fromClasspath(),
    jwt: StudioJwt = StudioJwt.fromEnv(),
    assignConfig: AssignRouteConfig = AssignRouteConfig.fromEnv(),
) {
    if (connectDb) {
        StudioDatabase.connect()
        seedDevEditorIfDev()
    }

    // Production prelude — request-id correlation + access log. Installed FIRST so the
    // request_id MDC entry and the per-request access line wrap every handler below,
    // including the metrics timer.
    install(StudioRequestIdPlugin)
    install(StudioRequestLogPlugin)

    // Dev-only CORS so the studio-web dev server (http://localhost:8082) can call this backend
    // cross-origin during a local Studio run. Gated on SDUI_ENV — production serves studio-web
    // same-origin and must not ship a cross-origin allowance.
    if (isDevEnv()) {
        install(CORS) {
            allowHost("localhost:8082", schemes = listOf("http"))
            allowHost("127.0.0.1:8082", schemes = listOf("http"))
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(STUDIO_CSRF_HEADER)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowCredentials = true
        }
    }

    // Uniform error contract. Installed before the routes so any exception escaping a handler —
    // notably a store-layer race under a unique index — is mapped to the standard ErrorResponse
    // JSON envelope (4xx/409/500) instead of leaking a bare 500. See [installStudioStatusPages].
    installStudioStatusPages()

    // Prometheus metrics — install BEFORE the rest of the plugins so the MicrometerMetrics
    // request timer wraps every route handler below. The /metrics scrape endpoint is
    // mounted alongside /health in the routing block.
    installMetrics()
    install(ContentNegotiation) { json(SduiJson) }
    install(WebSockets) {
        // Disable the default pingPeriod / timeout. Both schedule background coroutines that
        // outlive the route's session and trip `runTest` UncompletedCoroutinesError under
        // testApplication. The publisher's broadcast already detects dead peers via send
        // failure, so app-level pings are not load-bearing for liveness.
        pingPeriodMillis = 0
        timeoutMillis = 0
    }
    install(Authentication) {
        jwt(STUDIO_JWT_AUTH) {
            realm = StudioJwt.REALM
            verifier(jwt.verifier())
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
        }
    }

    // Graceful shutdown: drain the live bridge + sockets and close the pool so a rolling
    // deploy does not leak the Postgres LISTEN connection or the Hikari pool. runBlocking is
    // used deliberately here — this is the process teardown boundary (the counterpart to
    // main()), and the WebSocket close is a suspend call.
    monitor.subscribe(ApplicationStopping) {
        liveBridgeScope.cancel()
        runBlocking { livePublisher.closeAll() }
        (bus as? Closeable)?.close()
        // Only close the pool we own. In tests (connectDb = false) the DB lifecycle belongs to
        // the harness (StudioTestSupport.resetAndConnect / resetForTesting); closing it here
        // would race the next test's fixture.
        if (connectDb) StudioDatabase.shutdown()
    }

    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        installReadinessRoute()
        installMetricsRoute()
        // Subscribe-only fan-out for hot-reloading clients. Unauthenticated for now; future
        // hardening will require the same JWT as the admin surface but with a viewer role.
        installLiveScreensRoute(livePublisher, requireAuth = true, authProviderName = STUDIO_JWT_AUTH)
        installEditorAuthRoutes(jwt, STUDIO_JWT_AUTH)
        installScreensAdminRoutes(STUDIO_JWT_AUTH, notifier, validator)
        installVersionsAdminRoutes(STUDIO_JWT_AUTH)
        installAuditAdminRoutes(STUDIO_JWT_AUTH)
        // M-S6: A/B targeting + audiences + sticky assignments. Mounts the admin REST surface
        // under /experiments and /audiences (JWT-guarded), plus the /screens/{id}/assign route
        // consumed by upstream sample-servers — guarded by a service token + rate limiter
        // (see AssignRouteConfig) rather than the editor JWT.
        installExperimentRoutes(STUDIO_JWT_AUTH, assignConfig = assignConfig)
    }
}

/**
 * Build the default coroutine scope owning the live-bridge forwarders. A [SupervisorJob] keeps
 * one failing topic forwarder from tearing down the others; [Dispatchers.IO] suits the
 * WebSocket fan-out the forwarders drive. Cancelled by the `ApplicationStopping` hook.
 */
@Suppress("InjectDispatcher")
private fun newLiveBridgeScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
