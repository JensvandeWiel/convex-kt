/*
 * Copyright 2026 convex-kt contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.wynq.convex.client

import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.ErrorPayload
import eu.wynq.convex.core.value.ConvexValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Covers the one-off HTTP functions API against a mock engine. */
class ConvexHttpApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun postsArgumentsAndDecodesSuccess() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/query", request.url.encodedPath)
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"status":"success","value":{"${'$'}integer":"AQAAAAAAAAA="},"logLines":[]}""",
                headers = jsonHeaders,
            )
        }
        val api = ConvexHttpApi("https://example.convex.cloud", HttpClient(engine))
        val result = api.query(
            path = "messages:list",
            args = mapOf("room" to ConvexValue.String("a")),
            authHeader = "Bearer token",
        )
        assertEquals(ConvexResult.Success(ConvexValue.Int64(1)), result)
    }

    @Test
    fun mapsAnErrorResponseToFailure() = runTest {
        val engine = MockEngine {
            respond(content = """{"status":"error","errorMessage":"nope","logLines":[]}""", headers = jsonHeaders)
        }
        val api = ConvexHttpApi("https://example.convex.cloud", HttpClient(engine))
        val failure = assertIs<ConvexResult.Failure>(api.mutation("messages:send"))
        assertEquals(ErrorPayload.Message("nope"), failure.error)
    }

    @Test
    fun mapsAnErrorPayloadByKeyPresence() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"status":"error","errorMessage":"bad","errorData":"detail","logLines":[]}""",
                headers = jsonHeaders,
            )
        }
        val api = ConvexHttpApi("https://example.convex.cloud", HttpClient(engine))
        val failure = assertIs<ConvexResult.Failure>(api.action("tasks:run"))
        assertEquals(ErrorPayload.ErrorData("bad", ConvexValue.String("detail")), failure.error)
    }

    @Test
    fun reportsHttpErrors() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val api = ConvexHttpApi("https://example.convex.cloud", HttpClient(engine))
        assertFailsWith<ConvexClientException> { api.query("messages:list") }
    }
}
