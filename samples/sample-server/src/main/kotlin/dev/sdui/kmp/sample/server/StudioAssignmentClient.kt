package dev.sdui.kmp.sample.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Thin Ktor-client wrapper around the studio-server's `/screens/{id}/assign` route.
 *
 * Lives in the sample-server module by design — see docs/adr/0018-studio-ab-targeting-model.md
 * §"Sample-server delivery shape". The decision was between three options:
 *
 *  1. New `:studio-client` Gradle module that the sample depends on. Cleanest if multiple
 *     servers ever consult the studio, but adds module-graph noise and the verifyDependencyRules
 *     classifier has to gain a new tier.
 *  2. Put the client side in `:studio-server` itself. Wrong direction — the studio is a JVM
 *     admin app and this would force samples to drag in Postgres/HikariCP transitively.
 *  3. Inline the client in `:samples:sample-server`. Smallest blast radius. Picked.
 *
 * If a second consumer ever materialises (e.g. a dedicated edge-router server), promote this
 * file to `:studio-client` and the parents become a one-line `implementation(project(":studio-client"))`
 * swap.
 */
public class StudioAssignmentClient(
    public val baseUrl: String,
    engine: HttpClientEngine? = null,
) {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client: HttpClient = if (engine == null) {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
    } else {
        HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
    }

    /**
     * Resolve which screen body to serve [screenId] for [clientId]. [context] keys are
     * forwarded as `X-Sdui-Context-<key>` headers (e.g. `country` -> `X-Sdui-Context-Country`).
     *
     * Returns null if the studio is unreachable, returns a non-2xx status, or returns a body
     * without a `body` field. Callers MUST treat null as "fall back to the local default" —
     * never as an error. ADR-0018 §"Failure modes" documents the rationale.
     */
    public suspend fun assign(
        screenId: String,
        clientId: String,
        context: Map<String, String>,
    ): JsonElement? = runCatching {
        val response = client.get("${baseUrl.trimEnd('/')}/screens/$screenId/assign") {
            header(HEADER_CLIENT_ID, clientId)
            context.forEach { (k, v) ->
                if (v.isNotBlank()) header("$HEADER_CONTEXT_PREFIX${k.replaceFirstChar { it.uppercaseChar() }}", v)
            }
        }
        if (!response.status.isSuccess()) return@runCatching null
        val parsed = json.decodeFromString(AssignWireFormat.serializer(), response.bodyAsText())
        parsed.body
    }.getOrNull()

    /** Releases the underlying Ktor client. Idempotent. */
    public fun close() {
        client.close()
    }

    public companion object {
        public const val HEADER_CLIENT_ID: String = "X-Sdui-Client-Id"
        public const val HEADER_CONTEXT_PREFIX: String = "X-Sdui-Context-"
    }
}

/**
 * Wire shape returned by `/screens/{id}/assign`. Defined here so the sample-server doesn't
 * depend on `:studio-server` (which would drag Postgres into every sample build).
 */
@Serializable
internal data class AssignWireFormat(
    val screenId: String,
    val experimentId: String? = null,
    val variantId: String? = null,
    val screenVersionId: String? = null,
    val reason: String,
    val body: JsonElement? = null,
)
