package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** HTTP method for [Action.Submit]. */
@Serializable
public enum class HttpMethod { Get, Post, Put, Patch, Delete }

/** Whether an action executes online, queues offline, or runs purely locally. */
@Serializable
public enum class Execution { Online, OfflineQueue, LocalOnly }

/** Retry behavior for failed [Action.Submit] executions. M3 ships [None]; exponential lands in M5. */
@Serializable
public sealed interface RetryPolicy {
    @Serializable @SerialName("none") public data object None : RetryPolicy

    @Serializable @SerialName("exponential")
    public data class Exponential(
        public val maxAttempts: Int = 3,
        public val initialDelayMs: Long = 500,
    ) : RetryPolicy

    /**
     * Inert sentinel decoded when the `type` discriminator names a [RetryPolicy] this client
     * does not recognize. Dispatchers treat it exactly like [None] (a single attempt, no retry)
     * rather than throwing, so a newer retry strategy can never break an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable
    @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : RetryPolicy
}

/**
 * Client-side state changes applied before a [Action.Submit] round-trip completes. M3
 * declares the type but does NOT yet apply or roll back — that's M5 scope.
 */
@Serializable
public data class OptimisticUpdate(
    public val stateUpdates: Map<StatePath, Value<JsonElement>>,
    public val rollbackOnError: Boolean = true,
)

/** Execution + retry + idempotency knobs for an [Action.Submit]. */
@Serializable
public data class ActionPolicy(
    public val execution: Execution = Execution.Online,
    public val optimistic: OptimisticUpdate? = null,
    public val retry: RetryPolicy = RetryPolicy.None,
    public val idempotencyKey: StatePath? = null,
)
