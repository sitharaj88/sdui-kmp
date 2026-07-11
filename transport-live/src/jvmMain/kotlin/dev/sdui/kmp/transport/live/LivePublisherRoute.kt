package dev.sdui.kmp.transport.live

import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close

/**
 * Mount a subscribe-only WebSocket route at `"$pathPrefix/{id}"` that bridges connecting
 * sessions into [publisher]. The path's `{id}` segment becomes the broadcast topic, so a
 * client opening `ws://host/live/screens/home` joins the `home` topic and receives every
 * [dev.sdui.kmp.protocol.LiveEvent] subsequently broadcast to it.
 *
 * The handler holds the connection open for the lifetime of the WebSocket — it does not
 * read inbound frames because this is a server-push channel. On disconnect (clean or
 * abrupt) the session is removed from the topic in a `finally` block so the registry
 * cannot leak across reconnect storms.
 *
 * The application that owns [Route] must already have installed
 * [io.ktor.server.websocket.WebSockets]; this helper does not install it because most
 * apps configure pings/timeouts globally and per-route installation is not supported.
 *
 * **Auth.** When [requireAuth] is true (the production default) the route is wrapped in
 * an [authenticate] block keyed off [authProviderName]. The host must have a matching
 * `Authentication { jwt(authProviderName) { ... } }` (or any other provider) installed —
 * verify by reading [io.ktor.server.auth.Authentication] config in the host's module.
 * Set [requireAuth] to false in tests that want to drive the route without a token. With
 * auth enabled, an unauthenticated client receives a 401 on the upgrade request before
 * the WebSocket handshake completes.
 *
 * @param publisher the registry receiving register/unregister calls.
 * @param pathPrefix base path (no trailing slash). Defaults to `/live/screens` to match
 *   the studio + sample-server conventions.
 * @param requireAuth when true, gate the route behind [authProviderName]. Default-on so
 *   production hosts inherit the secure path; set to false only for tests.
 * @param authProviderName Ktor authentication provider name. Hosts choose this when they
 *   call `install(Authentication) { jwt("name") { ... } }`; sample-server uses `"jwt"`,
 *   studio-server uses [dev.sdui.kmp.studio.server.STUDIO_JWT_AUTH] (`"studio-jwt"`).
 */
public fun Route.installLiveScreensRoute(
    publisher: WebSocketLivePublisher,
    pathPrefix: String = "/live/screens",
    requireAuth: Boolean = true,
    authProviderName: String = "jwt",
) {
    val handler: io.ktor.server.routing.Route.() -> Unit = {
        webSocket("$pathPrefix/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                // Reject with a normal close so misconfigured clients see a deterministic end.
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing screen id"))
                return@webSocket
            }
            publisher.register(id, this)
            try {
                // Drain inbound frames so the channel stays alive; we do not handle client
                // messages on this subscribe-only route. The for-loop suspends until the peer
                // closes, at which point control returns and the finally block deregisters.
                for (frame in incoming) {
                    @Suppress("UnusedExpression")
                    frame
                }
            } finally {
                publisher.unregister(id, this)
            }
        }
    }
    if (requireAuth) {
        authenticate(authProviderName) { handler() }
    } else {
        handler()
    }
}
