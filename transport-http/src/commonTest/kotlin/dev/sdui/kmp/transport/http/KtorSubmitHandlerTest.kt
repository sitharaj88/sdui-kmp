package dev.sdui.kmp.transport.http

import dev.sdui.kmp.protocol.HttpMethod
import dev.sdui.kmp.runtime.SubmitResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private fun mockClient(record: MutableList<HttpRequestData>): HttpClient = HttpClient(
    MockEngine { request ->
        record += request
        respond(
            content = "{}",
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", "application/json"),
        )
    },
) {
    installSduiJson()
}

class KtorSubmitHandlerTest {

    @Test
    fun sets_x_idempotency_key_header_when_key_is_non_null() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = mockClient(captured)
        val handler = KtorSubmitHandler(client, baseUrl = "http://example.test")
        val result = handler.submit(
            endpoint = "/like",
            method = HttpMethod.Post,
            payload = buildJsonObject { put("liked", JsonPrimitive(true)) },
            idempotencyKey = "op-42",
        )
        assertIs<SubmitResult.Success>(result)
        assertEquals(1, captured.size)
        assertEquals("op-42", captured[0].headers[IDEMPOTENCY_KEY_HEADER])
        client.close()
    }

    @Test
    fun omits_x_idempotency_key_header_when_key_is_null() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = mockClient(captured)
        val handler = KtorSubmitHandler(client, baseUrl = "http://example.test")
        val result = handler.submit(
            endpoint = "/like",
            method = HttpMethod.Post,
            payload = buildJsonObject { put("liked", JsonPrimitive(true)) },
            idempotencyKey = null,
        )
        assertIs<SubmitResult.Success>(result)
        assertEquals(1, captured.size)
        assertNull(captured[0].headers[IDEMPOTENCY_KEY_HEADER])
        client.close()
    }

    @Test
    fun resolves_relative_endpoint_against_base_url() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = mockClient(captured)
        val handler = KtorSubmitHandler(client, baseUrl = "http://example.test/")
        handler.submit(
            endpoint = "/like",
            method = HttpMethod.Post,
            payload = buildJsonObject { },
            idempotencyKey = null,
        )
        assertEquals("http://example.test/like", captured[0].url.toString())
        client.close()
    }
}
