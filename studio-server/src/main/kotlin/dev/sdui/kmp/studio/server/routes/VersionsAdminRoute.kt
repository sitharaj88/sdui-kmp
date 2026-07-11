package dev.sdui.kmp.studio.server.routes

import dev.sdui.kmp.studio.server.db.ScreenStore
import dev.sdui.kmp.studio.server.model.VersionListItem
import dev.sdui.kmp.studio.server.rbac.Permission
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /admin/screens/{id}/versions` — paginated history for a single screen, newest first.
 * `?limit` defaults to [DEFAULT_LIMIT], `?offset` defaults to 0.
 *
 * Gated by `screens:read` so the editor must at least be able to see the screen — same
 * posture as `GET /admin/screens/{id}`.
 */
public fun Route.installVersionsAdminRoutes(jwtAuthName: String) {
    authenticate(jwtAuthName) {
        get("/admin/screens/{id}/versions") {
            call.requirePermission(Permission.SCREENS_READ) {
                val id = call.parameters["id"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT)
                    ?: DEFAULT_LIMIT
                val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val rows = ScreenStore.versionHistory(id, limit, offset).map { row ->
                    VersionListItem(
                        version = row.versionNumber,
                        createdAt = row.createdAt.toString(),
                        publishedAt = row.publishedAt?.toString(),
                        editorId = row.editorId.toString(),
                    )
                }
                call.respond(HttpStatusCode.OK, rows)
            }
        }
    }
}

private const val DEFAULT_LIMIT: Int = 50
private const val MAX_LIMIT: Int = 200
