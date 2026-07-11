# ADR 0003 — Submit handler is a SAM in `:runtime`, not Ktor-backed

**Status:** accepted (Phase 3, M3)

## Context

`Action.Submit` issues an HTTP request with a JSON body built from state paths. The
dispatcher needs to run that request when the action fires.

## Problem

The obvious implementation — `DefaultActionDispatcher` depends on `io.ktor:ktor-client-core`
and calls `client.request(...)` directly — works but pollutes `:runtime`'s dependency graph.
`:runtime` is Tier 2 and is supposed to be a pure Compose + protocol module; pulling in an
HTTP transport couples the renderer to network concerns.

## Decision

Extract the seam:

```kotlin
// :runtime
fun interface SubmitHandler {
    suspend fun submit(endpoint: String, method: HttpMethod, payload: JsonObject): SubmitResult
}

sealed interface SubmitResult {
    data object Success : SubmitResult
    data class Failure(val statusCode: Int? = null, val cause: Throwable? = null) : SubmitResult
}
```

`DefaultActionDispatcher` takes an optional `SubmitHandler`. When null, Submit falls
through to `onError` — non-HTTP hosts (tests, previews) don't need HTTP on the classpath.

Ktor-backed implementation lives in `:transport-http` as `KtorSubmitHandler(client, baseUrl)`.

## Consequences

- `:runtime` has zero Ktor dependency. Test infrastructure can fake submits in-memory.
- Hosts that never dispatch Submit (previews, offline-only apps) skip the handler entirely.
- Alternative submit implementations (gRPC, GraphQL, Protobuf) slot behind the same SAM without touching the dispatcher.
- Two places know about `HttpMethod`: the protocol (wire type) and `KtorSubmitHandler`. Runtime maps enum → Ktor method in one place.
