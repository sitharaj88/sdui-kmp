package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Target of a [Action.Navigate].
 *
 * Protocol v0 ships only [ScreenDest] and [Back]; [Modal], [TabSwitch], and [PopToRoot] are
 * deferred to later milestones but the sealed hierarchy is open-ended via additive evolution.
 */
@Serializable
public sealed interface Destination {
    /** Push a new screen by route with optional serialized args. */
    @Serializable
    @SerialName("screen")
    public data class ScreenDest(
        public val route: String,
        public val args: JsonObject = JsonObject(emptyMap()),
    ) : Destination

    /** Pop [count] screens off the back stack. */
    @Serializable
    @SerialName("back")
    public data class Back(public val count: Int = 1) : Destination

    /** Present a screen modally (sheet or full-screen cover depending on platform). */
    @Serializable
    @SerialName("modal")
    public data class Modal(
        public val route: String,
        public val args: JsonObject = JsonObject(emptyMap()),
    ) : Destination

    /** Switch to tab [tabId] inside the enclosing [dev.sdui.kmp.protocol.NavHost]. */
    @Serializable
    @SerialName("tab")
    public data class TabSwitch(public val tabId: String) : Destination

    /** Pop the current stack to its root. */
    @Serializable
    @SerialName("pop_to_root")
    public data object PopToRoot : Destination

    /**
     * Inert sentinel decoded when the `type` discriminator names a [Destination] this client
     * does not recognize. Navigators treat it as a no-op rather than throwing, so a newer
     * navigation target can never blank the screen on an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable
    @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : Destination
}
