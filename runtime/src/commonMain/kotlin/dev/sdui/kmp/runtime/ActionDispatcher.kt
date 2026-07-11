package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.OptimisticUpdate
import dev.sdui.kmp.protocol.RetryPolicy
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Value
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Carries out an [Action] at the moment it fires.
 *
 * M3 handled [Action.Navigate], [Action.UpdateState], [Action.Sequence], [Action.When], and a
 * synchronous [Action.Submit]. M5 layers on optimistic state updates with rollback, and
 * exponential-backoff retry driven by the action's [dev.sdui.kmp.protocol.ActionPolicy].
 */
public interface ActionDispatcher {
    public suspend fun dispatch(action: Action)
}

/**
 * Default in-process dispatcher.
 *
 * [submitHandler] is required to execute [Action.Submit]; when null, Submit actions fall
 * straight to their `onError` branch. `:transport-http` ships a Ktor-backed handler — host
 * apps that never use Submit may omit it. Keeping this seam transport-agnostic is deliberate:
 * `:runtime` must stay a pure Compose + protocol module with no HTTP dependency.
 */
public class DefaultActionDispatcher(
    private val store: StateStore,
    private val navigator: Navigator,
    private val telemetry: SduiTelemetry = NoopTelemetry,
    private val submitHandler: SubmitHandler? = null,
) : ActionDispatcher {

    override suspend fun dispatch(action: Action) {
        val mark = TimeSource.Monotonic.markNow()
        when (action) {
            is Action.Navigate -> navigator.navigate(action.destination, action.replace)
            is Action.UpdateState -> {
                val resolved = resolveValue(action.value)
                if (resolved != null) store.update(action.path, resolved)
            }
            is Action.Sequence -> action.actions.forEach { dispatch(it) }
            is Action.When -> {
                val branch = if (action.condition.evaluate(store)) action.then else action.otherwise
                branch.forEach { dispatch(it) }
            }
            is Action.Submit -> submit(action)
            // An action added by a newer server that this client cannot decode: dispatching it
            // is a deliberate no-op so forward-compatible actions never crash the client.
            is Action.Unknown -> Unit
        }
        telemetry.onActionDispatched(action, mark.elapsedNow().inWholeMilliseconds)
    }

    private suspend fun submit(action: Action.Submit) {
        val optimistic = action.policy.optimistic
        val priorState = optimistic?.let { captureSnapshot(it) }
        optimistic?.let { applyOptimistic(it) }

        val body = buildJsonObject {
            action.payload.forEach { (key, path) ->
                store.read(path)?.let { put(key, it) }
            }
        }
        val idempotencyKey = action.policy.idempotencyKey?.let { path ->
            (store.read(path) as? JsonPrimitive)?.content
        }
        val result =
            if (submitHandler == null) SubmitResult.Failure() else performWithRetry(action, body, idempotencyKey)

        if (result !is SubmitResult.Success && optimistic?.rollbackOnError == true && priorState != null) {
            rollback(priorState)
        }

        val branch = if (result is SubmitResult.Success) action.onSuccess else action.onError
        branch.forEach { dispatch(it) }
    }

    private suspend fun performWithRetry(
        action: Action.Submit,
        body: JsonObject,
        idempotencyKey: String?,
    ): SubmitResult {
        val handler = submitHandler ?: return SubmitResult.Failure()
        val retry = action.policy.retry
        val maxAttempts = when (retry) {
            is RetryPolicy.Exponential -> retry.maxAttempts.coerceAtLeast(1)
            // Unknown retry policy from a newer server degrades to a single attempt, like None.
            RetryPolicy.None, is RetryPolicy.Unknown -> 1
        }
        val initialDelay = when (retry) {
            is RetryPolicy.Exponential -> retry.initialDelayMs.coerceAtLeast(1)
            RetryPolicy.None, is RetryPolicy.Unknown -> 0L
        }
        var attempt = 0
        var last: SubmitResult = SubmitResult.Failure()
        while (attempt < maxAttempts) {
            last = handler.submit(action.endpoint, action.method, body, idempotencyKey)
            if (last is SubmitResult.Success) return last
            attempt++
            if (attempt >= maxAttempts) break
            if (retry is RetryPolicy.Exponential) {
                // Cap shift so overflow can't bite after many attempts.
                val shift = (attempt - 1).coerceAtMost(20)
                delay(initialDelay shl shift)
            }
        }
        return last
    }

    private fun captureSnapshot(optimistic: OptimisticUpdate): Map<StatePath, JsonElement?> =
        optimistic.stateUpdates.keys.associateWith { store.read(it) }

    private fun applyOptimistic(optimistic: OptimisticUpdate) {
        optimistic.stateUpdates.forEach { (path, value) ->
            val resolved = resolveValue(value) ?: return@forEach
            store.update(path, resolved)
        }
    }

    private fun rollback(prior: Map<StatePath, JsonElement?>) {
        prior.forEach { (path, value) ->
            if (value != null) store.update(path, value) else store.remove(path)
        }
    }

    private fun resolveValue(value: Value<JsonElement>): JsonElement? = when (value) {
        is Value.Literal<JsonElement> -> value.value
        is Value.Bind<JsonElement> -> store.read(value.path)
        is Value.Template -> null // Template is Value<String>; cannot appear at the JsonElement position.
        is Value.Unknown -> null // A value kind added by a newer server: treat as absent.
    }
}
