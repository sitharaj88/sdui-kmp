package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.ActionPolicy
import dev.sdui.kmp.protocol.HttpMethod
import dev.sdui.kmp.protocol.OptimisticUpdate
import dev.sdui.kmp.protocol.RetryPolicy
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private class SequencedHandler(private val results: MutableList<SubmitResult>) : SubmitHandler {
    var calls: Int = 0
        private set
    val idempotencyKeys: MutableList<String?> = mutableListOf()
    override suspend fun submit(
        endpoint: String,
        method: HttpMethod,
        payload: JsonObject,
        idempotencyKey: String?,
    ): SubmitResult {
        calls++
        idempotencyKeys += idempotencyKey
        return if (results.isEmpty()) SubmitResult.Failure() else results.removeAt(0)
    }
}

private class NopNav : Navigator {
    override val current = androidx.compose.runtime.mutableStateOf<String?>(null)
    override fun navigate(destination: dev.sdui.kmp.protocol.Destination, replace: Boolean) {}
    override fun push(route: String) {}
    override fun replace(route: String) {}
    override fun pop(count: Int) {}
    override fun popToRoot() {}
    override fun switchTab(tabId: String) {}
}

class OptimisticAndRetryTest {

    @Test
    fun optimistic_update_applies_immediately_and_persists_on_success() = runTest {
        val store = StateStore(mapOf(StatePath("likes") to JsonPrimitive(5)))
        val handler = SequencedHandler(mutableListOf(SubmitResult.Success))
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/like",
                policy = ActionPolicy(
                    optimistic = OptimisticUpdate(
                        stateUpdates = mapOf(StatePath("likes") to Value.ofJson(JsonPrimitive(6))),
                    ),
                ),
            ),
        )
        assertEquals(JsonPrimitive(6), store.read(StatePath("likes")))
        assertEquals(1, handler.calls)
    }

    @Test
    fun rollback_restores_prior_value_on_failure() = runTest {
        val store = StateStore(mapOf(StatePath("likes") to JsonPrimitive(5)))
        val handler = SequencedHandler(mutableListOf(SubmitResult.Failure(statusCode = 500)))
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/like",
                policy = ActionPolicy(
                    optimistic = OptimisticUpdate(
                        stateUpdates = mapOf(StatePath("likes") to Value.ofJson(JsonPrimitive(6))),
                        rollbackOnError = true,
                    ),
                ),
            ),
        )
        assertEquals(JsonPrimitive(5), store.read(StatePath("likes")))
    }

    @Test
    fun rollback_removes_key_if_it_was_unset_before_optimistic_write() = runTest {
        val store = StateStore()
        val handler = SequencedHandler(mutableListOf(SubmitResult.Failure()))
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/x",
                policy = ActionPolicy(
                    optimistic = OptimisticUpdate(
                        stateUpdates = mapOf(StatePath("fresh") to Value.ofJson(JsonPrimitive("temp"))),
                    ),
                ),
            ),
        )
        assertNull(store.read(StatePath("fresh")))
    }

    @Test
    fun rollbackOnError_false_keeps_optimistic_write() = runTest {
        val store = StateStore(mapOf(StatePath("likes") to JsonPrimitive(5)))
        val handler = SequencedHandler(mutableListOf(SubmitResult.Failure()))
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/like",
                policy = ActionPolicy(
                    optimistic = OptimisticUpdate(
                        stateUpdates = mapOf(StatePath("likes") to Value.ofJson(JsonPrimitive(6))),
                        rollbackOnError = false,
                    ),
                ),
            ),
        )
        assertEquals(JsonPrimitive(6), store.read(StatePath("likes")))
    }

    @Test
    fun retry_exponential_attempts_until_maxAttempts_on_repeated_failure() = runTest {
        val store = StateStore()
        val handler = SequencedHandler(
            mutableListOf(
                SubmitResult.Failure(statusCode = 500),
                SubmitResult.Failure(statusCode = 500),
                SubmitResult.Failure(statusCode = 500),
            ),
        )
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/x",
                policy = ActionPolicy(
                    retry = RetryPolicy.Exponential(maxAttempts = 3, initialDelayMs = 1),
                ),
                onError = listOf(
                    Action.UpdateState(
                        path = StatePath("terminal"),
                        value = Value.ofJson(JsonPrimitive("error")),
                    ),
                ),
            ),
        )
        assertEquals(3, handler.calls)
        assertEquals(JsonPrimitive("error"), store.read(StatePath("terminal")))
    }

    @Test
    fun retry_stops_early_when_a_later_attempt_succeeds() = runTest {
        val store = StateStore()
        val handler = SequencedHandler(
            mutableListOf(
                SubmitResult.Failure(statusCode = 500),
                SubmitResult.Success,
            ),
        )
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/x",
                policy = ActionPolicy(
                    retry = RetryPolicy.Exponential(maxAttempts = 4, initialDelayMs = 1),
                ),
                onSuccess = listOf(
                    Action.UpdateState(
                        path = StatePath("terminal"),
                        value = Value.ofJson(JsonPrimitive("ok")),
                    ),
                ),
            ),
        )
        assertEquals(2, handler.calls)
        assertEquals(JsonPrimitive("ok"), store.read(StatePath("terminal")))
    }

    @Test
    fun retry_none_policy_makes_exactly_one_attempt() = runTest {
        val store = StateStore()
        val handler = SequencedHandler(mutableListOf(SubmitResult.Failure()))
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/x",
                policy = ActionPolicy(retry = RetryPolicy.None),
            ),
        )
        assertEquals(1, handler.calls)
    }

    @Test
    fun idempotency_key_is_resolved_from_state_store_and_passed_to_handler() = runTest {
        val store = StateStore(
            mapOf(StatePath("op.id") to JsonPrimitive("op-42")),
        )
        val handler = SequencedHandler(mutableListOf(SubmitResult.Success))
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/x",
                policy = ActionPolicy(idempotencyKey = StatePath("op.id")),
            ),
        )
        assertEquals(listOf<String?>("op-42"), handler.idempotencyKeys)
    }

    @Test
    fun idempotency_key_is_null_when_policy_omits_it() = runTest {
        val store = StateStore()
        val handler = SequencedHandler(mutableListOf(SubmitResult.Success))
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(Action.Submit(endpoint = "/x"))
        assertEquals(listOf<String?>(null), handler.idempotencyKeys)
    }

    @Test
    fun idempotency_key_is_null_when_path_unset_in_store() = runTest {
        val store = StateStore()
        val handler = SequencedHandler(mutableListOf(SubmitResult.Success))
        val dispatcher = DefaultActionDispatcher(store, NopNav(), submitHandler = handler)
        dispatcher.dispatch(
            Action.Submit(
                endpoint = "/x",
                policy = ActionPolicy(idempotencyKey = StatePath("missing")),
            ),
        )
        assertEquals(listOf<String?>(null), handler.idempotencyKeys)
    }
}
