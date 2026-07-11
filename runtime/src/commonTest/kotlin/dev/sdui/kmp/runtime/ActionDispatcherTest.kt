package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.HttpMethod
import dev.sdui.kmp.protocol.Predicate
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private class RecordingNavigator : Navigator {
    val calls: MutableList<Pair<Destination, Boolean>> = mutableListOf()
    override val current = androidx.compose.runtime.mutableStateOf<String?>(null)
    override fun navigate(destination: Destination, replace: Boolean) {
        calls += destination to replace
    }
    override fun push(route: String) {}
    override fun replace(route: String) {}
    override fun pop(count: Int) {}
    override fun popToRoot() {}
    override fun switchTab(tabId: String) {}
}

private class CannedSubmitHandler(private val result: SubmitResult) : SubmitHandler {
    var lastEndpoint: String? = null
    var lastMethod: HttpMethod? = null
    var lastPayload: JsonObject? = null
    var lastIdempotencyKey: String? = null
    override suspend fun submit(
        endpoint: String,
        method: HttpMethod,
        payload: JsonObject,
        idempotencyKey: String?,
    ): SubmitResult {
        lastEndpoint = endpoint
        lastMethod = method
        lastPayload = payload
        lastIdempotencyKey = idempotencyKey
        return result
    }
}

class ActionDispatcherTest {

    @Test
    fun update_state_with_literal_writes_to_store() = runTest {
        val store = StateStore()
        val nav = RecordingNavigator()
        val dispatcher = DefaultActionDispatcher(store, nav)
        dispatcher.dispatch(
            Action.UpdateState(
                path = StatePath("k"),
                value = Value.ofJson(JsonPrimitive("v")),
            ),
        )
        assertEquals(JsonPrimitive("v"), store.read(StatePath("k")))
    }

    @Test
    fun update_state_with_bind_copies_value_across_paths() = runTest {
        val store = StateStore(mapOf(StatePath("src") to JsonPrimitive(42)))
        val dispatcher = DefaultActionDispatcher(store, RecordingNavigator())
        dispatcher.dispatch(
            Action.UpdateState(
                path = StatePath("dst"),
                value = Value.Bind(StatePath("src")),
            ),
        )
        assertEquals(JsonPrimitive(42), store.read(StatePath("dst")))
    }

    @Test
    fun navigate_delegates_to_navigator() = runTest {
        val nav = RecordingNavigator()
        val dispatcher = DefaultActionDispatcher(StateStore(), nav)
        dispatcher.dispatch(Action.Navigate(Destination.ScreenDest("/x"), replace = true))
        assertEquals(1, nav.calls.size)
        assertEquals(Destination.ScreenDest("/x") to true, nav.calls[0])
    }

    @Test
    fun sequence_runs_actions_in_order() = runTest {
        val store = StateStore()
        val nav = RecordingNavigator()
        val dispatcher = DefaultActionDispatcher(store, nav)
        dispatcher.dispatch(
            Action.Sequence(
                actions = listOf(
                    Action.UpdateState(StatePath("a"), Value.ofJson(JsonPrimitive(1))),
                    Action.UpdateState(StatePath("b"), Value.ofJson(JsonPrimitive(2))),
                ),
            ),
        )
        assertEquals(JsonPrimitive(1), store.read(StatePath("a")))
        assertEquals(JsonPrimitive(2), store.read(StatePath("b")))
    }

    @Test
    fun when_chooses_then_branch_when_condition_true() = runTest {
        val store = StateStore(mapOf(StatePath("flag") to JsonPrimitive(true)))
        val dispatcher = DefaultActionDispatcher(store, RecordingNavigator())
        dispatcher.dispatch(
            Action.When(
                condition = Predicate.Eq(StatePath("flag"), JsonPrimitive(true)),
                then = listOf(Action.UpdateState(StatePath("branch"), Value.ofJson(JsonPrimitive("then")))),
                otherwise = listOf(Action.UpdateState(StatePath("branch"), Value.ofJson(JsonPrimitive("else")))),
            ),
        )
        assertEquals(JsonPrimitive("then"), store.read(StatePath("branch")))
    }

    @Test
    fun when_chooses_otherwise_branch_when_condition_false() = runTest {
        val store = StateStore(mapOf(StatePath("flag") to JsonPrimitive(false)))
        val dispatcher = DefaultActionDispatcher(store, RecordingNavigator())
        dispatcher.dispatch(
            Action.When(
                condition = Predicate.Eq(StatePath("flag"), JsonPrimitive(true)),
                then = listOf(Action.UpdateState(StatePath("branch"), Value.ofJson(JsonPrimitive("then")))),
                otherwise = listOf(Action.UpdateState(StatePath("branch"), Value.ofJson(JsonPrimitive("else")))),
            ),
        )
        assertEquals(JsonPrimitive("else"), store.read(StatePath("branch")))
    }

    @Test
    fun submit_success_runs_on_success_branch() = runTest {
        val store = StateStore(
            mapOf(
                StatePath("email") to JsonPrimitive("a@b.c"),
                StatePath("password") to JsonPrimitive("hunter2"),
            ),
        )
        val handler = CannedSubmitHandler(SubmitResult.Success)
        val dispatcher = DefaultActionDispatcher(store, RecordingNavigator(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/login",
                method = HttpMethod.Post,
                payload = mapOf(
                    "email" to StatePath("email"),
                    "password" to StatePath("password"),
                ),
                onSuccess = listOf(Action.UpdateState(StatePath("status"), Value.ofJson(JsonPrimitive("ok")))),
                onError = listOf(Action.UpdateState(StatePath("status"), Value.ofJson(JsonPrimitive("fail")))),
            ),
        )
        assertEquals("/login", handler.lastEndpoint)
        assertEquals(HttpMethod.Post, handler.lastMethod)
        assertEquals(JsonPrimitive("a@b.c"), handler.lastPayload?.get("email"))
        assertEquals(JsonPrimitive("ok"), store.read(StatePath("status")))
    }

    @Test
    fun submit_failure_runs_on_error_branch() = runTest {
        val store = StateStore()
        val dispatcher = DefaultActionDispatcher(
            store,
            RecordingNavigator(),
            submitHandler = CannedSubmitHandler(SubmitResult.Failure(statusCode = 401)),
        )
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/auth",
                onSuccess = listOf(Action.UpdateState(StatePath("status"), Value.ofJson(JsonPrimitive("ok")))),
                onError = listOf(Action.UpdateState(StatePath("status"), Value.ofJson(JsonPrimitive("fail")))),
            ),
        )
        assertEquals(JsonPrimitive("fail"), store.read(StatePath("status")))
    }

    @Test
    fun submit_without_handler_falls_through_to_on_error() = runTest {
        val store = StateStore()
        val dispatcher = DefaultActionDispatcher(store, RecordingNavigator(), submitHandler = null)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/auth",
                onError = listOf(Action.UpdateState(StatePath("status"), Value.ofJson(JsonPrimitive("no_handler")))),
            ),
        )
        assertEquals(JsonPrimitive("no_handler"), store.read(StatePath("status")))
    }

    @Test
    fun nested_sequences_and_when_compose() = runTest {
        val store = StateStore(mapOf(StatePath("flag") to JsonPrimitive(true)))
        val dispatcher = DefaultActionDispatcher(store, RecordingNavigator())
        dispatcher.dispatch(
            Action.Sequence(
                actions = listOf(
                    Action.UpdateState(StatePath("count"), Value.ofJson(JsonPrimitive(1))),
                    Action.When(
                        condition = Predicate.Eq(StatePath("flag"), JsonPrimitive(true)),
                        then = listOf(
                            Action.Sequence(
                                actions = listOf(
                                    Action.UpdateState(StatePath("inner"), Value.ofJson(JsonPrimitive("a"))),
                                    Action.UpdateState(StatePath("inner"), Value.ofJson(JsonPrimitive("b"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(JsonPrimitive(1), store.read(StatePath("count")))
        assertEquals(JsonPrimitive("b"), store.read(StatePath("inner")))
        assertTrue(true)
    }
}
