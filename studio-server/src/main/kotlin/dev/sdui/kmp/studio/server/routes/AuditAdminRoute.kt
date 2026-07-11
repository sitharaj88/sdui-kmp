package dev.sdui.kmp.studio.server.routes

import dev.sdui.kmp.studio.server.db.AuditEntry
import dev.sdui.kmp.studio.server.db.AuditQuery
import dev.sdui.kmp.studio.server.db.AuditStore
import dev.sdui.kmp.studio.server.model.AuditListItem
import dev.sdui.kmp.studio.server.model.ErrorResponse
import dev.sdui.kmp.studio.server.rbac.Permission
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Audit log routes:
 *
 *  - `GET /admin/audit` — paginated lookup. Filters: `screenId`, `editorId`, `from`, `to`.
 *    Pagination: legacy `?offset=` AND newer `?cursor=` (preferred — stable across inserts).
 *    Permission: `audit:read`.
 *  - `GET /audit/export` — streamed export of the same filter window in CSV / JSON / JSONL.
 *    Permission: `audit:export`. Uses [respondTextWriter] for CSV/JSONL and
 *    [respondOutputStream] for JSON, so the response body is a forward-only write — memory
 *    stays bounded regardless of result size.
 */
@Suppress("LongMethod")
public fun Route.installAuditAdminRoutes(jwtAuthName: String) {
    authenticate(jwtAuthName) {
        get("/admin/audit") {
            call.requirePermission(Permission.AUDIT_READ) {
                val params = call.request.queryParameters
                val editorId = parseEditorId(params["editorId"]) { reason ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid editorId: $reason"))
                    return@requirePermission
                }
                val from = parseInstant(params["from"]) { reason ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid from: $reason"))
                    return@requirePermission
                }
                val to = parseInstant(params["to"]) { reason ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid to: $reason"))
                    return@requirePermission
                }
                val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
                val offset = params["offset"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val cursor = parseCursor(params["cursor"]) { reason ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid cursor: $reason"))
                    return@requirePermission
                }

                val rows = AuditStore.query(
                    AuditQuery(
                        screenId = params["screenId"]?.takeIf { it.isNotBlank() },
                        editorId = editorId,
                        from = from,
                        to = to,
                        limit = limit,
                        offset = if (cursor != null) 0L else offset,
                        cursorAt = cursor?.first,
                        cursorId = cursor?.second,
                    ),
                ).map { entry -> entry.toListItem() }

                // Surface the next-page cursor in a response header so callers can paginate
                // without parsing the body. Empty when this is the final page.
                rows.lastOrNull()?.let { last ->
                    call.response.header(NEXT_CURSOR_HEADER, "${last.at}|${last.id}")
                }
                call.respond(HttpStatusCode.OK, rows)
            }
        }

        get("/audit/export") {
            call.requirePermission(Permission.AUDIT_EXPORT) {
                val params = call.request.queryParameters
                val editorId = parseEditorId(params["editorId"]) { reason ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid editorId: $reason"))
                    return@requirePermission
                }
                val from = parseInstant(params["from"]) { reason ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid from: $reason"))
                    return@requirePermission
                }
                val to = parseInstant(params["to"]) { reason ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid to: $reason"))
                    return@requirePermission
                }
                val format = (params["format"] ?: "csv").lowercase()
                val filter = AuditQuery(
                    screenId = params["screenId"]?.takeIf { it.isNotBlank() },
                    editorId = editorId,
                    from = from,
                    to = to,
                    // Bound the export to keep blast radius predictable; CSV with 100k rows is
                    // ~30MB which a browser handles. Larger windows can paginate via /admin/audit.
                    limit = MAX_EXPORT,
                )
                when (format) {
                    "csv" -> call.exportCsv(filter)
                    "json" -> call.exportJson(filter)
                    "jsonl" -> call.exportJsonl(filter)
                    else -> call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("unsupported format: $format (must be csv|json|jsonl)"),
                    )
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.exportCsv(filter: AuditQuery) {
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"studio-audit.csv\"")
    respondTextWriter(contentType = ContentType.Text.CSV) {
        write(CSV_HEADER)
        write("\n")
        AuditStore.stream(filter) { entry ->
            write(entry.toCsvLine())
            write("\n")
        }
        flush()
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.exportJsonl(filter: AuditQuery) {
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"studio-audit.jsonl\"")
    respondTextWriter(contentType = ContentType.parse("application/x-ndjson")) {
        AuditStore.stream(filter) { entry ->
            write(entryAsJsonObject(entry))
            write("\n")
        }
        flush()
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.exportJson(filter: AuditQuery) {
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"studio-audit.json\"")
    respondOutputStream(contentType = ContentType.Application.Json) {
        val writer = bufferedWriter()
        writer.write("[")
        var first = true
        AuditStore.stream(filter) { entry ->
            if (!first) writer.write(",")
            writer.write(entryAsJsonObject(entry))
            first = false
        }
        writer.write("]")
        writer.flush()
    }
}

private fun AuditEntry.toListItem(): AuditListItem = AuditListItem(
    id = id.toString(),
    screenId = screenId,
    editorId = editorId.toString(),
    action = action.wire,
    fromVersion = fromVersion,
    toVersion = toVersion,
    at = at.toString(),
    requestId = requestId,
    actorIp = actorIp,
    userAgent = userAgent,
)

private fun AuditEntry.toCsvLine(): String {
    // RFC-4180 quoting: any field containing `"`, `,`, `\r` or `\n` is double-quoted with
    // embedded quotes doubled. Numeric / nullable fields stay bare.
    val cols = listOf(
        id.toString(),
        screenId,
        editorId.toString(),
        action.wire,
        fromVersion?.toString().orEmpty(),
        toVersion?.toString().orEmpty(),
        at.toString(),
        requestId,
        actorIp.orEmpty(),
        userAgent.orEmpty(),
    )
    return cols.joinToString(",") { csvQuote(it) }
}

private fun csvQuote(value: String): String {
    val needs = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needs) return value
    return '"' + value.replace("\"", "\"\"") + '"'
}

private fun entryAsJsonObject(entry: AuditEntry): String {
    val obj = buildJsonObject {
        put("id", JsonPrimitive(entry.id.toString()))
        put("screenId", JsonPrimitive(entry.screenId))
        put("editorId", JsonPrimitive(entry.editorId.toString()))
        put("action", JsonPrimitive(entry.action.wire))
        put("fromVersion", entry.fromVersion?.let { JsonPrimitive(it) } ?: JsonNull)
        put("toVersion", entry.toVersion?.let { JsonPrimitive(it) } ?: JsonNull)
        put("at", JsonPrimitive(entry.at.toString()))
        put("requestId", JsonPrimitive(entry.requestId))
        put("actorIp", entry.actorIp?.let { JsonPrimitive(it) } ?: JsonNull)
        put("userAgent", entry.userAgent?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    return EXPORT_JSON.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
}

private inline fun parseInstant(raw: String?, onInvalid: (String) -> Nothing): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw)
    } catch (e: DateTimeParseException) {
        onInvalid(e.message ?: "not ISO-8601")
    }
}

private inline fun parseEditorId(raw: String?, onInvalid: (String) -> Nothing): UUID? {
    if (raw.isNullOrBlank()) return null
    return runCatching { UUID.fromString(raw) }.getOrElse { onInvalid("UUID") }
}

private inline fun parseCursor(
    raw: String?,
    onInvalid: (String) -> Nothing,
): Pair<Instant, UUID>? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split('|', limit = 2)
    if (parts.size != 2) onInvalid("expected '<at>|<id>'")
    val at = runCatching { Instant.parse(parts[0]) }.getOrElse { onInvalid("at: ${it.message}") }
    val id = runCatching { UUID.fromString(parts[1]) }.getOrElse { onInvalid("id: ${it.message}") }
    return at to id
}

private const val DEFAULT_LIMIT: Int = 50
private const val MAX_LIMIT: Int = 500
private const val MAX_EXPORT: Int = 100_000
private const val NEXT_CURSOR_HEADER: String = "X-Next-Cursor"
private const val CSV_HEADER: String =
    "id,screenId,editorId,action,fromVersion,toVersion,at,requestId,actorIp,userAgent"

private val EXPORT_JSON: Json = Json { encodeDefaults = true }
