package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class FormsAndActionsRoundTripTest {
    private val json: Json = SduiJson

    @Test
    fun value_template_roundtrips() {
        val v: Value<String> = Value.template(
            pattern = "Hello {name}, age {age}",
            bindings = mapOf("name" to StatePath("n"), "age" to StatePath("a")),
        )
        val s = Value.serializer(String.serializer())
        assertEquals(v, json.decodeFromString(s, json.encodeToString(s, v)))
    }

    @Test
    fun action_sequence_roundtrips() {
        val a: Action = Action.Sequence(
            actions = listOf(
                Action.UpdateState(StatePath("x"), Value.ofJson(JsonPrimitive(1))),
                Action.Navigate(Destination.Back()),
            ),
        )
        assertEquals(a, json.decodeFromString(Action.serializer(), json.encodeToString(Action.serializer(), a)))
    }

    @Test
    fun action_when_roundtrips() {
        val a: Action = Action.When(
            condition = Predicate.Eq(StatePath("ready"), JsonPrimitive(true)),
            then = listOf(Action.Navigate(Destination.ScreenDest("/yes"))),
            otherwise = listOf(Action.Navigate(Destination.ScreenDest("/no"))),
        )
        assertEquals(a, json.decodeFromString(Action.serializer(), json.encodeToString(Action.serializer(), a)))
    }

    @Test
    fun action_submit_roundtrips_with_policy_and_handlers() {
        val a: Action = Action.Submit(
            endpoint = "/auth/login",
            method = HttpMethod.Post,
            payload = mapOf("email" to StatePath("login.email"), "password" to StatePath("login.password")),
            policy = ActionPolicy(
                execution = Execution.Online,
                retry = RetryPolicy.Exponential(maxAttempts = 4, initialDelayMs = 250),
                idempotencyKey = StatePath("idempotency.key"),
            ),
            onSuccess = listOf(Action.Navigate(Destination.ScreenDest("/home"))),
            onError = listOf(Action.UpdateState(StatePath("error"), Value.ofJson(JsonPrimitive("bad")))),
        )
        assertEquals(a, json.decodeFromString(Action.serializer(), json.encodeToString(Action.serializer(), a)))
    }

    @Test
    fun text_field_roundtrips_with_validation() {
        val node: UiNode = TextField(
            id = NodeId("email"),
            path = StatePath("login.email"),
            placeholder = Value.ofString("email"),
            keyboard = Keyboard.Email,
            validation = Validation.All(
                validations = listOf(Validation.Required(), Validation.Email()),
            ),
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun checkbox_roundtrips() {
        val node: UiNode = Checkbox(
            id = NodeId("remember"),
            path = StatePath("login.remember"),
            label = Value.ofString("Remember me"),
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun every_validation_variant_roundtrips() {
        val variants: List<Validation> = listOf(
            Validation.Required(),
            Validation.MinLength(8),
            Validation.MaxLength(20, message = Value.ofString("too long")),
            Validation.Email(),
            Validation.All(listOf(Validation.Required(), Validation.Email())),
        )
        variants.forEach {
            assertEquals(
                it,
                json.decodeFromString(Validation.serializer(), json.encodeToString(Validation.serializer(), it)),
            )
        }
    }
}

