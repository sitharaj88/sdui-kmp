package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * What happens when the user taps a button, submits a form, or triggers any event.
 *
 * Actions are data, never lambdas — this is what lets us queue them offline, replay them for
 * debugging, attach retry policies, and instrument them uniformly. Protocol v0 ships
 * [Navigate] and [UpdateState]; [Submit], [Sequence], and [When] arrive in M3.
 */
@Serializable
public sealed interface Action {
    /** Ask the host's navigator to move to [destination]. */
    @Serializable
    @SerialName("navigate")
    public data class Navigate(
        public val destination: Destination,
        public val replace: Boolean = false,
    ) : Action

    /** Write [value] into the state store at [path]. */
    @Serializable
    @SerialName("update_state")
    public data class UpdateState(
        public val path: StatePath,
        public val value: Value<JsonElement>,
    ) : Action

    /** Run every action in [actions] in order. Failures in one action do not stop the next. */
    @Serializable
    @SerialName("sequence")
    public data class Sequence(public val actions: List<Action>) : Action

    /** Evaluate [condition]; run [then] when true, [otherwise] when false. */
    @Serializable
    @SerialName("when")
    public data class When(
        public val condition: Predicate,
        public val then: List<Action>,
        public val otherwise: List<Action> = emptyList(),
    ) : Action

    /**
     * Submit a payload to [endpoint]. M3 behavior is synchronous: await the HTTP response,
     * run [onSuccess] on 2xx or [onError] otherwise. Offline queueing, retry, and optimistic
     * updates ship in M5 via [policy].
     */
    @Serializable
    @SerialName("submit")
    public data class Submit(
        public val endpoint: String,
        public val method: HttpMethod = HttpMethod.Post,
        public val payload: Map<String, StatePath> = emptyMap(),
        public val policy: ActionPolicy = ActionPolicy(),
        public val onSuccess: List<Action> = emptyList(),
        public val onError: List<Action> = emptyList(),
    ) : Action

    /**
     * Inert sentinel decoded when the `type` discriminator names an [Action] this client does
     * not recognize — typically a newer server emitting an action added after this client
     * shipped.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it. Dispatching it is a deliberate no-op, so a forward-compatible action can
     * never blank the screen on an older client (non-negotiable #3 applied to actions).
     */
    @Serializable
    @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : Action
}
