package dev.sdui.kmp.sample.server

import dev.sdui.kmp.auth.rs256.CsrfPlugin
import dev.sdui.kmp.auth.rs256.EnvSecretsProvider
import dev.sdui.kmp.auth.rs256.RateLimitPlugin
import dev.sdui.kmp.auth.rs256.Rs256JwtIssuer
import dev.sdui.kmp.auth.rs256.Rs256JwtVerifier
import dev.sdui.kmp.auth.rs256.SecretsProvider
import dev.sdui.kmp.auth.rs256.installJwksEndpoint
import dev.sdui.kmp.auth.rs256.loadOrGenerateKeyPair
import dev.sdui.kmp.protocol.A11y
import dev.sdui.kmp.protocol.A11yRole
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.EdgeInsets
import dev.sdui.kmp.protocol.Keyboard
import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.StateDeclaration
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.StateScope
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.protocol.Validation
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.sample.server.db.Db
import dev.sdui.kmp.sample.server.db.IdempotencyPlugin
import dev.sdui.kmp.sample.server.db.RequestIdPlugin
import dev.sdui.kmp.sample.server.db.RequestLogPlugin
import dev.sdui.kmp.sample.server.db.SessionStore
import dev.sdui.kmp.sample.server.db.installReadinessRoute
import dev.sdui.kmp.server.screen
import dev.sdui.kmp.transport.live.InProcessLiveBus
import dev.sdui.kmp.transport.live.LiveBus
import dev.sdui.kmp.transport.live.PostgresLivePublisher
import dev.sdui.kmp.transport.live.WebSocketLivePublisher
import dev.sdui.kmp.transport.live.bridgeAllTopics
import dev.sdui.kmp.transport.live.installLiveScreensRoute
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Production-shaped JWT configuration for the sample server.
 *
 * Replaces the previous HMAC256-with-hardcoded-secret object with an asymmetric RS256 issuer
 * + verifier sourced from `:auth-rs256`. Public keys are published at
 * `/.well-known/jwks.json` so federated services can verify tokens themselves; the private
 * key never leaves this process. Keys come from a [SecretsProvider] (env-var-backed in the
 * default wiring; swap for `VaultSecretsProvider` to fetch from HashiCorp Vault). When no
 * keys are configured, an ephemeral pair is generated at boot — fine for dev but tokens
 * become unverifiable on restart.
 */
internal object SampleJwt {
    const val ISSUER: String = "sdui-kmp-sample"
    const val AUDIENCE: String = "sdui-kmp-sample-clients"
    const val REALM: String = "sdui-kmp-sample"
    const val KEY_ID: String = "sdui-sample-key-1"

    /** Override-able from tests via [setSecretsProvider]; defaults to env-var lookup. */
    @Volatile
    private var secretsProvider: SecretsProvider = EnvSecretsProvider()

    @Volatile
    private var cachedIssuer: Rs256JwtIssuer? = null

    @Volatile
    private var cachedVerifier: Rs256JwtVerifier? = null

    /**
     * Test seam: replace the secrets provider before the module boots. Resets cached issuer
     * and verifier so the next [issuer] / [verifier] call rebuilds.
     */
    internal fun setSecretsProvider(provider: SecretsProvider) {
        secretsProvider = provider
        cachedIssuer = null
        cachedVerifier = null
    }

    /** Lazily-built issuer. First access generates or loads the key pair. */
    fun issuer(): Rs256JwtIssuer {
        cachedIssuer?.let { return it }
        synchronized(this) {
            cachedIssuer?.let { return it }
            val keyPair = loadOrGenerateKeyPair(secretsProvider)
            val issuer = Rs256JwtIssuer(
                keyPair = keyPair,
                keyId = KEY_ID,
                issuer = ISSUER,
                audience = AUDIENCE,
                expiry = 1.hours,
            )
            cachedIssuer = issuer
            return issuer
        }
    }

    /** Companion verifier with the same public key as [issuer]. */
    fun verifier(): Rs256JwtVerifier {
        cachedVerifier?.let { return it }
        synchronized(this) {
            cachedVerifier?.let { return it }
            val verifier = Rs256JwtVerifier(
                publicKey = issuer().publicKey,
                issuer = ISSUER,
                audience = AUDIENCE,
            )
            cachedVerifier = verifier
            return verifier
        }
    }
}

/**
 * Production-shaped sample server. Demonstrates real-world auth wiring:
 *
 *  - `POST /auth/login` accepts `{password: "password"}` for the demo and mints an RS256 JWT
 *    whose `jti` claim is the [SessionStore] row id (so the server can revoke).
 *  - `POST /auth/logout` revokes the current session row, immediately invalidating the JWT.
 *  - `GET /.well-known/jwks.json` publishes the issuer's RSA public key.
 *  - `/auth/login` is rate-limited (5 req/min/IP) via the in-memory limiter from `:auth-rs256`.
 *  - Mutating endpoints under `/auth/` require a matching CSRF cookie + `X-CSRF-Token` header
 *    (double-submit pattern). Clients first GET `/auth/csrf` to mint the cookie.
 *  - All `screens/...` routes require `Authorization: Bearer <token>` and additionally check
 *    the embedded `jti` is still active in [SessionStore] — revoked tokens are rejected with
 *    401 even if the JWT itself has not yet expired.
 *  - `/health` and `/live/ticker` stay unauthenticated.
 *
 * Run with `./gradlew :samples:sample-server:run`. Port defaults to 8080; override via `PORT`.
 */
public fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { sampleModule() }.start(wait = true)
}

/**
 * Application module for the sample server. Extracted so [io.ktor.server.testing.testApplication]
 * can boot it without a real Netty engine.
 */
internal fun Application.sampleModule() {
    sampleModuleInternal(connectDb = true)
}

/**
 * Test-only seam: lets [ReadinessTest] boot the module *without* connecting the DB so the
 * `/readiness` 503 path can be exercised end-to-end. Production callers always use
 * [sampleModule], which sets `connectDb = true`.
 */
internal fun Application.sampleModuleNoDb() {
    sampleModuleInternal(connectDb = false)
}

/**
 * Production-engineering prelude. Owns the database connection, request-id correlation,
 * structured access log, idempotency-key replay, and the /readiness probe. Confined to a
 * single helper so parallel work on screen routes never collides with this block.
 */
private fun Application.installProductionPrelude(connectDb: Boolean) {
    if (connectDb) Db.connect()
    install(RequestIdPlugin)
    install(RequestLogPlugin)
    install(IdempotencyPlugin)
    // Prometheus metrics — must run BEFORE routing so the MicrometerMetrics plugin's
    // request timer wraps every handler. The /metrics scrape endpoint itself is
    // mounted in the routing block below alongside /readiness.
    installMetrics()
    routing {
        installReadinessRoute()
        installMetricsRoute()
    }
}

private const val LOGIN_RATE_LIMIT_PER_MINUTE: Int = 5

/**
 * Per-process [WebSocketLivePublisher] backing the `/live/screens/{id}` route. Cross-process
 * fan-out is now handled by a [LiveBus] — when `LIVE_BACKEND=postgres` the bus is a
 * [PostgresLivePublisher] sharing the same database the sample uses for sessions, so a
 * publish from the studio-server JVM lands on every sample-server JVM connected to the
 * same cluster. The default ([InProcessLiveBus]) keeps the existing single-process behaviour
 * for tests and the dev wiring where everything runs in one process.
 *
 * The bridge between [samplePublisher] and the bus is launched in [installLivePublishStack]
 * for the topics the sample exposes; new screens added to the sample need to grow that list.
 */
private val samplePublisher: WebSocketLivePublisher = WebSocketLivePublisher()

/** Process-scoped supervisor for the cross-process bridge. Cancelled on shutdown. */
@Suppress("InjectDispatcher")
private val liveBridgeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Package-private hook for tests that want to swap in a deterministic bus. */
@Volatile
private var liveBusOverride: LiveBus? = null

internal fun setLiveBusForTesting(bus: LiveBus?) {
    liveBusOverride = bus
}

internal const val LIVE_BACKEND_ENV: String = "LIVE_BACKEND"
internal const val LIVE_BACKEND_POSTGRES: String = "postgres"

/** Topics this sample knows about. Bridged into the local WS publisher on boot. */
internal val SAMPLE_LIVE_TOPICS: List<String> = listOf(
    "home",
    "about",
    "login",
    "feed",
    "tracking",
    "tabs",
    "native-demo",
)

@Volatile
private var liveBridgeStarted: Boolean = false

/**
 * Ensure the cross-process [LiveBus] bridge is started exactly once per JVM. Honours
 * [liveBusOverride] (test seam) before reading [LIVE_BACKEND_ENV] from the environment.
 * Tests that boot the module multiple times (e.g. `testApplication { ... }` × N) reuse
 * the same bridge — a per-test scope would race the prior test's pending notifications
 * and produce flake.
 */
@Synchronized
private fun ensureLiveBridge() {
    if (liveBridgeStarted) return
    val bus = liveBusOverride ?: resolveLiveBusFromEnv() ?: InProcessLiveBus()
    bridgeAllTopics(
        bus = bus,
        publisher = samplePublisher,
        topics = SAMPLE_LIVE_TOPICS,
        scope = liveBridgeScope,
    )
    liveBridgeStarted = true
}

/**
 * Build a [PostgresLivePublisher] when the operator opted in via `LIVE_BACKEND=postgres` and
 * the sample is connected to a real Postgres data source. Returns null otherwise so callers
 * can fall back to [InProcessLiveBus]. Reading [Db]'s pooled DataSource is pragmatic — the
 * sample only owns one — but real apps would inject the bus from outside.
 */
private fun resolveLiveBusFromEnv(): LiveBus? {
    val backend = System.getenv(LIVE_BACKEND_ENV)?.lowercase()?.takeIf { it.isNotBlank() }
    if (backend != LIVE_BACKEND_POSTGRES) return null
    if (!Db.isConnected) return null
    if (Db.isFallback) return null // H2 fallback can't actually do LISTEN/NOTIFY
    return PostgresLivePublisher(dataSource = Db.dataSourceForLiveBackend())
}

private fun Application.sampleModuleInternal(connectDb: Boolean) {
    installProductionPrelude(connectDb)
    ensureLiveBridge()
    install(ContentNegotiation) { json(SduiJson) }
    install(WebSockets)
    install(Authentication) {
        jwt("jwt") {
            realm = SampleJwt.REALM
            verifier(SampleJwt.verifier().verifier())
            validate { credential ->
                val subject = credential.payload.subject ?: return@validate null
                val jti = credential.payload.id ?: return@validate null
                val sessionId = runCatching { UUID.fromString(jti) }.getOrNull() ?: return@validate null
                // Revocation check: even if the JWT is otherwise valid, refuse if the
                // session row has been revoked (or, in the no-DB test path, doesn't exist).
                val active = if (Db.isConnected) {
                    runCatching { runBlocking { SessionStore.isActive(sessionId) } }.getOrDefault(false)
                } else {
                    // No DB — accept any well-formed token. Used by ReadinessTest.
                    true
                }
                if (!active) null else JWTPrincipal(credential.payload)
            }
        }
    }
    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }

        // Public JWKS document so federated services can verify our tokens without sharing
        // the private key. Trusted by clients via the `iss` + `kid` pair.
        installJwksEndpoint(issuer = SampleJwt.issuer())

        // Subscribe-only fan-out for hot-reloading clients. Per-screen topic; bearer JWT
        // required (default for installLiveScreensRoute) — production has the same wire
        // shape as the protected /screens/* HTTP routes. Tests that need an open path use
        // requireAuth = false on a separate route. ADR-0022 documents the contract.
        installLiveScreensRoute(samplePublisher, requireAuth = true, authProviderName = "jwt")

        // Demo live stream: emits a StateUpdate every 2 seconds bumping a counter. Clients
        // that render a Text bound to StatePath("ticker") see it update in real time.
        webSocket("/live/ticker") {
            var n = 0
            while (true) {
                val event: LiveEvent = LiveEvent.StateUpdate(
                    updates = mapOf(StatePath("ticker") to JsonPrimitive(n)),
                )
                send(Frame.Text(SduiJson.encodeToString(LiveEvent.serializer(), event)))
                n++
                delay(2.seconds)
            }
        }

        // CSRF mint endpoint. Clients GET this once on app boot to obtain the
        // `csrf-token` cookie, then echo the value in `X-CSRF-Token` on every mutating
        // call to `/auth/*`. Plugin's default mintOn predicate covers `/screens/*`, so we
        // explicitly install a CSRF plugin under /auth that *also* mints on this path.
        route("/auth") {
            install(CsrfPlugin) {
                // Mint on a known GET path under /auth so we don't depend on a screen request first.
                mintOn = { call -> call.request.local.method.value == "GET" && call.request.local.uri == "/auth/csrf" }
                // Enforce on every mutating method under this route.
            }

            // Rate-limit login attempts independently of CSRF so a flood from a single IP
            // does not consume DB capacity for session writes.
            route("/login") {
                install(RateLimitPlugin) {
                    requestsPerMinute = LOGIN_RATE_LIMIT_PER_MINUTE
                }

                // Mock auth. Anything but "password" fails — makes the error path exercisable.
                // The password check is intentionally trivial; the JWT issuance itself is real.
                post {
                    val body = call.receive<JsonObject>()
                    val password = body["password"]?.jsonPrimitive?.content.orEmpty()
                    val email = body["email"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: "demo@sdui.dev"
                    if (password == "password") {
                        val session = SessionStore.issue(subject = email)
                        val token = SampleJwt.issuer().issue(
                            subject = email,
                            claims = mapOf("jti" to session.id.toString()),
                        )
                        call.respondText(
                            """{"token":"$token"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.OK,
                        )
                    } else {
                        call.respondText(
                            """{"error":"invalid credentials"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                    }
                }
            }

            // CSRF mint endpoint — sets the cookie if absent. Body is empty because the
            // double-submit pattern doesn't need to expose the token in JSON; the cookie is
            // readable by JS and that's enough.
            get("/csrf") {
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }

            // Logout — requires a valid JWT, revokes its session row, returns 204.
            authenticate("jwt") {
                post("/logout") {
                    val principal = call.principal<JWTPrincipal>()
                    val jti = principal?.payload?.id
                    val sessionId = jti?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    if (sessionId != null) {
                        SessionStore.revoke(sessionId)
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        authenticate("jwt") {
            // M-S6 wiring: every screen route consults the studio's `/screens/{id}/assign` if
            // STUDIO_BASE_URL is set. On any failure (env unset, network error, non-2xx) we
            // fall through to the locally-defined screen so the existing E2E tests keep
            // passing untouched. ADR-0018 documents the failure-mode contract.
            get("/screens/home") { serveWithStudio("home") { call.respond(homeScreen()) } }
            get("/screens/about") { serveWithStudio("about") { call.respond(aboutScreen()) } }
            get("/screens/login") { serveWithStudio("login") { call.respond(loginScreen()) } }
            get("/screens/feed") { serveWithStudio("feed") { call.respond(feedScreen()) } }
            get("/screens/tracking") { serveWithStudio("tracking") { call.respond(trackingScreen()) } }
            get("/screens/tabs") { serveWithStudio("tabs") { call.respond(tabsScreen()) } }
            // Native-surface demo: exercises the widgets-native-map factory end-to-end. Hosts
            // without a factory registered for "sdui.map" render the fallback Text inside
            // the NativeSurface; hosts with the factory render either Google Maps (Android,
            // real key configured) or the cross-platform Material 3 placeholder.
            get("/screens/native-demo") {
                serveWithStudio("native-demo") { call.respond(nativeDemoScreen()) }
            }
        }
    }
}

// --- M-S6: studio-driven screen serving --------------------------------------------------------

/**
 * Process-wide [StudioAssignmentClient] — null when [STUDIO_BASE_URL_ENV] is unset. Lazily
 * built so a fresh checkout without studio configured pays no boot cost.
 *
 * Test seam: `setStudioAssignmentClientForTesting` lets E2E tests (notably
 * `StudioAssignmentTest`) inject a `MockEngine`-backed client that resolves against an
 * in-memory studio simulator.
 */
@Volatile
private var studioAssignmentClient: StudioAssignmentClient? = null

@Volatile
private var studioAssignmentClientResolved: Boolean = false

internal fun setStudioAssignmentClientForTesting(client: StudioAssignmentClient?) {
    studioAssignmentClient?.close()
    studioAssignmentClient = client
    studioAssignmentClientResolved = true
}

internal fun resetStudioAssignmentClientForTesting() {
    studioAssignmentClient?.close()
    studioAssignmentClient = null
    studioAssignmentClientResolved = false
}

private fun resolveStudioClient(): StudioAssignmentClient? {
    if (studioAssignmentClientResolved) return studioAssignmentClient
    synchronized(StudioAssignmentClient::class.java) {
        if (studioAssignmentClientResolved) return studioAssignmentClient
        val baseUrl = System.getenv(STUDIO_BASE_URL_ENV)?.takeIf { it.isNotBlank() }
        studioAssignmentClient = baseUrl?.let { StudioAssignmentClient(it) }
        studioAssignmentClientResolved = true
        return studioAssignmentClient
    }
}

/**
 * Try the studio's assignment service for [screenId]; on null (env unset, network failure,
 * non-2xx, malformed body) fall through to [fallback]. The block is invoked synchronously so
 * the response semantics are identical to the pre-M-S6 wiring.
 */
internal suspend fun io.ktor.server.routing.RoutingContext.serveWithStudio(
    screenId: String,
    fallback: suspend () -> Unit,
) {
    val client = resolveStudioClient()
    if (client == null) {
        fallback()
        return
    }
    val clientId = call.resolveSduiClientId()
    val context = call.collectSduiContext()
    val body = client.assign(screenId = screenId, clientId = clientId, context = context)
    if (body == null) {
        fallback()
        return
    }
    call.respond(body)
}

private fun ApplicationCall.resolveSduiClientId(): String {
    request.headers[StudioAssignmentClient.HEADER_CLIENT_ID]?.takeIf { it.isNotBlank() }?.let { return it }
    // Fall back: stable hash of bearer token claims (sub + jti). Keeps repeated requests from
    // the same session sticky without leaking the actual token to the studio.
    val principal = principal<JWTPrincipal>()
    val sub = principal?.payload?.subject
    val jti = principal?.payload?.id
    if (!sub.isNullOrBlank() && !jti.isNullOrBlank()) {
        return "session-${(sub + "|" + jti).hashCode()}"
    }
    // Last resort: per-request UUID. The studio will treat this as a fresh client, so the
    // first call is randomly assigned and there is no stickiness — fine for unauthenticated
    // anonymous traffic where no other identity is available.
    return "anon-${UUID.randomUUID()}"
}

private fun ApplicationCall.collectSduiContext(): Map<String, String> {
    val out = mutableMapOf<String, String>()
    request.headers.forEach { name, values ->
        if (name.startsWith(StudioAssignmentClient.HEADER_CONTEXT_PREFIX, ignoreCase = true)) {
            val key = name.substring(StudioAssignmentClient.HEADER_CONTEXT_PREFIX.length).lowercase()
            val v = values.firstOrNull().orEmpty()
            if (v.isNotEmpty()) out[key] = v
        }
    }
    return out
}

internal const val STUDIO_BASE_URL_ENV: String = "STUDIO_BASE_URL"

internal fun homeScreen(): Screen = screen(id = "home") {
    column(spacing = Spacing.Md, padding = EdgeInsets.all(Spacing.Lg)) {
        text("Welcome to sdui-kmp", style = TextStyleToken.Heading)
        text("This screen was emitted by the server.")
        button(
            label = "Go to About",
            action = Action.Navigate(Destination.ScreenDest("/about")),
        )
        button(
            label = "Log in",
            action = Action.Navigate(Destination.ScreenDest("/login")),
        )
        button(
            label = "Feed demo",
            action = Action.Navigate(Destination.ScreenDest("/feed")),
        )
        button(
            label = "Tracking demo",
            action = Action.Navigate(Destination.ScreenDest("/tracking")),
        )
        button(
            label = "Native map demo",
            action = Action.Navigate(Destination.ScreenDest("/native-demo")),
        )
    }
}

/**
 * Demo screen for the `widgets-native-map` factory. Emits a [dev.sdui.kmp.protocol.NativeSurface]
 * of `kind = "sdui.map"` carrying a couple of static markers.
 *
 * Clients without the factory registered render the inline `Text` fallback. Android clients
 * that registered `MapSurfaceFactory.instance(requireApiKey = true)` and ship a placeholder
 * key see the cross-platform Material 3 card; clients with a real key see Google Maps.
 */
@Suppress("LongMethod")
internal fun nativeDemoScreen(): Screen = screen(id = "native-demo") {
    column(spacing = Spacing.Md, padding = EdgeInsets.all(Spacing.Lg)) {
        text("Native map demo", style = TextStyleToken.Heading)
        val mapBlurb = "Renders Google Maps on Android (when a real API key is configured), " +
            "MKMapView on iOS, and a placeholder Card on Desktop / Wasm."
        text(mapBlurb)
        nativeSurface(
            kind = "sdui.map",
            config = buildJsonObject {
                put("center_lat", JsonPrimitive(37.7749))
                put("center_lng", JsonPrimitive(-122.4194))
                put("zoom", JsonPrimitive(13))
                put(
                    "markers",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive("pickup"))
                                put("lat", JsonPrimitive(37.78))
                                put("lng", JsonPrimitive(-122.41))
                                put("title", JsonPrimitive("Pickup"))
                            },
                        )
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive("dropoff"))
                                put("lat", JsonPrimitive(37.77))
                                put("lng", JsonPrimitive(-122.42))
                                put("title", JsonPrimitive("Dropoff"))
                            },
                        )
                    },
                )
            },
            events = mapOf(
                "markerTapped" to listOf(
                    Action.UpdateState(
                        path = StatePath("nativeDemo.lastTap"),
                        value = Value.Bind(StatePath("markerTapped.id")),
                    ),
                ),
            ),
            fallback = dev.sdui.kmp.protocol.Text(
                id = dev.sdui.kmp.protocol.NodeId("native-demo/map_fallback"),
                content = Value.ofString("Map unavailable on this client."),
                color = ColorToken.Muted,
            ),
        )
        text(
            content = template(
                pattern = "Last tapped marker: {id}",
                "id" to "nativeDemo.lastTap",
            ),
        )
        button(
            label = "Back",
            action = Action.Navigate(Destination.Back()),
        )
    }
}

internal fun aboutScreen(): Screen = screen(id = "about") {
    column(spacing = Spacing.Md, padding = EdgeInsets.all(Spacing.Lg)) {
        text("About", style = TextStyleToken.Heading)
        text("A server-driven UI framework for Kotlin Multiplatform.")
        button(
            label = "Back",
            action = Action.Navigate(Destination.Back()),
        )
    }
}

internal fun trackingScreen(): Screen = screen(id = "tracking") {
    state(
        StateDeclaration(
            path = StatePath("driver.name"),
            scope = StateScope.Screen,
            initial = JsonPrimitive("Sam"),
        ),
    )
    state(
        StateDeclaration(
            path = StatePath("driver.eta_min"),
            scope = StateScope.Screen,
            initial = JsonPrimitive(8),
        ),
    )
    state(
        StateDeclaration(
            path = StatePath("driver.avatar"),
            scope = StateScope.Screen,
            initial = JsonPrimitive("https://example.com/driver.jpg"),
        ),
    )
    column(spacing = Spacing.Md, padding = EdgeInsets.all(Spacing.Lg)) {
        text("Tracking your order", style = TextStyleToken.Heading)
        // Map native surface — clients without a factory render the fallback text.
        nativeSurface(
            kind = "sdui.map",
            config = buildJsonObject {
                put("center_lat", JsonPrimitive(37.7749))
                put("center_lng", JsonPrimitive(-122.4194))
                put("zoom", JsonPrimitive(13))
            },
            bindings = mapOf("driver_position" to StatePath("driver.location")),
            fallback = dev.sdui.kmp.protocol.Text(
                id = dev.sdui.kmp.protocol.NodeId("tracking/map_fallback"),
                content = Value.ofString("Map unavailable on this client."),
                color = ColorToken.Muted,
            ),
        )
        asyncImage(
            url = Value.Bind(StatePath("driver.avatar")),
            contentDescription = Value.ofString("Driver avatar"),
        )
        text(
            content = template(
                pattern = "{name} — arriving in {eta} min",
                "name" to "driver.name",
                "eta" to "driver.eta_min",
            ),
            style = TextStyleToken.Title,
        )
        button(
            label = "Back",
            action = Action.Navigate(Destination.Back()),
        )
    }
}

internal fun feedScreen(): Screen = screen(id = "feed") {
    val items = (1..8).map { i ->
        buildJsonObject {
            put("id", JsonPrimitive("item-$i"))
            put("title", JsonPrimitive("Feed item #$i"))
            put("liked", JsonPrimitive(false))
        }
    }
    column(spacing = Spacing.Md, padding = EdgeInsets.all(Spacing.Lg)) {
        text("Feed", style = TextStyleToken.Heading)
        text("Each Like toggles state scoped to that row.")
        lazyList(
            source = ListSource.Inline(items),
            itemKeyPath = StatePath("id"),
            spacing = Spacing.Sm,
        ) {
            column(spacing = Spacing.Xs, padding = EdgeInsets.all(Spacing.Sm)) {
                text(Value.Bind(StatePath("title")), style = TextStyleToken.Title)
                button(
                    label = template(
                        pattern = "Liked: {liked}",
                        "liked" to "liked",
                    ),
                    action = Action.UpdateState(
                        path = StatePath("liked"),
                        // Node-scoped flip — the template always resolves against the item scope.
                        value = Value.ofJson(JsonPrimitive(true)),
                    ),
                )
            }
        }
        button(
            label = "Back",
            action = Action.Navigate(Destination.Back()),
        )
    }
}

internal fun loginScreen(): Screen = screen(id = "login") {
    state(StateDeclaration(StatePath("login.email"), StateScope.Screen, JsonPrimitive("")))
    state(StateDeclaration(StatePath("login.password"), StateScope.Screen, JsonPrimitive("")))
    state(StateDeclaration(StatePath("login.remember"), StateScope.Screen, JsonPrimitive(false)))
    state(StateDeclaration(StatePath("login.error"), StateScope.Screen, JsonPrimitive("")))

    column(spacing = Spacing.Md, padding = EdgeInsets.all(Spacing.Lg)) {
        text("Log in", style = TextStyleToken.Heading)
        textField(
            path = StatePath("login.email"),
            placeholder = Value.ofString("Email"),
            keyboard = Keyboard.Email,
            validation = Validation.All(
                validations = listOf(
                    Validation.Required(message = Value.ofString("Email is required")),
                    Validation.Email(message = Value.ofString("Not a valid email")),
                ),
            ),
            a11y = A11y(role = A11yRole.TextField, label = Value.ofString("Email")),
        )
        textField(
            path = StatePath("login.password"),
            placeholder = Value.ofString("Password"),
            keyboard = Keyboard.Password,
            secure = true,
            validation = Validation.MinLength(
                length = 8,
                message = Value.ofString("Minimum 8 characters"),
            ),
            a11y = A11y(role = A11yRole.TextField, label = Value.ofString("Password")),
        )
        checkbox(
            path = StatePath("login.remember"),
            label = Value.ofString("Remember me"),
        )
        button(
            label = "Submit",
            action = Action.Submit(
                endpoint = "/auth/login",
                payload = mapOf(
                    "email" to StatePath("login.email"),
                    "password" to StatePath("login.password"),
                    "remember" to StatePath("login.remember"),
                ),
                onSuccess = listOf(
                    Action.UpdateState(
                        path = StatePath("login.error"),
                        value = Value.ofJson(JsonPrimitive("")),
                    ),
                    Action.Navigate(Destination.ScreenDest("/home"), replace = true),
                ),
                onError = listOf(
                    Action.UpdateState(
                        path = StatePath("login.error"),
                        value = Value.ofJson(JsonPrimitive("Invalid credentials")),
                    ),
                ),
            ),
        )
        // Show the error message only when set.
        text(
            content = Value.Bind(StatePath("login.error")),
            style = TextStyleToken.BodySmall,
            color = ColorToken.Error,
        )
    }
}
