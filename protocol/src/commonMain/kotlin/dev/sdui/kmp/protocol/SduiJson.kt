package dev.sdui.kmp.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * The `SerializersModule` every host must use to decode `sdui-kmp` JSON.
 *
 * Registers a polymorphic default deserializer for **every** wire-crossing sealed hierarchy so a
 * node, action, value, token, or any other polymorphic payload with an unrecognized `type`
 * discriminator decodes to that hierarchy's inert `Unknown` sentinel instead of throwing. This is
 * what makes the "client never crashes on an unknown wire type" invariant hold by construction —
 * not just for [UiNode] but for the whole protocol surface, so adding an [Action], [Destination],
 * token, or other case in a newer server cannot blank the screen on an older client.
 */
public val SduiSerializersModule: SerializersModule = SerializersModule {
    polymorphic(UiNode::class) {
        defaultDeserializer { type -> UnknownUiNodeDeserializer(type.orEmpty()) }
    }
    polymorphic(Action::class) {
        defaultDeserializer { type -> unknownSentinel<Action>("__unknown_action__", type) { Action.Unknown(it) } }
    }
    polymorphic(Value::class) {
        defaultDeserializer { type -> unknownSentinel<Value<*>>("__unknown_value__", type) { Value.Unknown(it) } }
    }
    polymorphic(Predicate::class) {
        defaultDeserializer { type -> unknownSentinel<Predicate>("__unknown_predicate__", type) { Predicate.Unknown(it) } }
    }
    polymorphic(Validation::class) {
        defaultDeserializer { type -> unknownSentinel<Validation>("__unknown_validation__", type) { Validation.Unknown(it) } }
    }
    polymorphic(Destination::class) {
        defaultDeserializer { type -> unknownSentinel<Destination>("__unknown_destination__", type) { Destination.Unknown(it) } }
    }
    polymorphic(RetryPolicy::class) {
        defaultDeserializer { type -> unknownSentinel<RetryPolicy>("__unknown_retry_policy__", type) { RetryPolicy.Unknown(it) } }
    }
    polymorphic(PatchOp::class) {
        defaultDeserializer { type -> unknownSentinel<PatchOp>("__unknown_patch_op__", type) { PatchOp.Unknown(it) } }
    }
    polymorphic(ListSource::class) {
        defaultDeserializer { type -> unknownSentinel<ListSource>("__unknown_list_source__", type) { ListSource.Unknown(it) } }
    }
    polymorphic(LiveEvent::class) {
        defaultDeserializer { type -> unknownSentinel<LiveEvent>("__unknown_live_event__", type) { LiveEvent.Unknown(it) } }
    }
    polymorphic(ColorToken::class) {
        defaultDeserializer { type -> unknownSentinel<ColorToken>("__unknown_color_token__", type) { ColorToken.Unknown(it) } }
    }
    polymorphic(IconToken::class) {
        defaultDeserializer { type -> unknownSentinel<IconToken>("__unknown_icon_token__", type) { IconToken.Unknown(it) } }
    }
}

/**
 * Build a deserialize-only strategy that discards an unrecognized polymorphic payload and yields
 * the hierarchy's inert [make] sentinel, tagging it with the original `type` discriminator.
 */
private fun <T> unknownSentinel(
    descriptorName: String,
    originalType: String?,
    make: (originalType: String) -> T,
): DeserializationStrategy<T> = UnknownSentinelDeserializer(descriptorName, originalType.orEmpty(), make)

@OptIn(ExperimentalSerializationApi::class)
private class UnknownSentinelDeserializer<out T>(
    descriptorName: String,
    private val originalType: String,
    private val make: (originalType: String) -> T,
) : DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(descriptorName)

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("$descriptor only decodes from JSON")
        // Consume and discard the unknown payload so the surrounding decoder stays positioned.
        jsonDecoder.decodeJsonElement()
        return make(originalType)
    }
}

/**
 * Ready-to-use [Json] configured for the `sdui-kmp` protocol.
 *
 * Host apps that need a customized [Json] (e.g. pretty-printing) should copy these options,
 * in particular `classDiscriminator = "type"` and [SduiSerializersModule].
 */
public val SduiJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
    // Forward-compatibility: an unknown enum case emitted by a newer server must not blank the
    // screen on an older client (non-negotiable #2 — additive enum growth). With this flag, an
    // unrecognized enum value decodes to the field's DEFAULT instead of throwing. Coercion only
    // fires for a field that declares a default, so every wire-facing enum field must carry one
    // (an `Unknown`/`Unspecified`-style neutral default) for this to fully hold.
    coerceInputValues = true
    serializersModule = SduiSerializersModule
}

@OptIn(ExperimentalSerializationApi::class)
private class UnknownUiNodeDeserializer(private val originalType: String) : KSerializer<UnknownUiNode> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("__unknown__") {
        element<NodeId>("id", isOptional = true)
        element<SchemaVersion>("since", isOptional = true)
        element<UiNode?>("fallback", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): UnknownUiNode {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("UnknownUiNode only decodes from JSON")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val id = obj["id"]
            ?.let { jsonDecoder.json.decodeFromJsonElement(NodeId.serializer(), it) }
            ?: NodeId("")
        val since = obj["since"]
            ?.let { jsonDecoder.json.decodeFromJsonElement(SchemaVersion.serializer(), it) }
            ?: SchemaVersion(0)
        val fallback = obj["fallback"]?.takeIf { it !is JsonNull }
            ?.let { jsonDecoder.json.decodeFromJsonElement(UiNode.serializer(), it) }
        return UnknownUiNode(id, since, fallback, originalType)
    }

    override fun serialize(encoder: Encoder, value: UnknownUiNode) {
        error("UnknownUiNode is a client-side decode sentinel and must not be encoded")
    }
}
