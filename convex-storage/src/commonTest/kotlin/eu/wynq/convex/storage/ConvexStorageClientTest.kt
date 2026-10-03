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
package eu.wynq.convex.storage

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the storage transfer client against a mock engine, so request shapes
 * and response parsing are checked without a live backend.
 */
class ConvexStorageClientTest {

    @Test
    fun uploadsBytesAndReadsStorageId() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://upload.example/abc", request.url.toString())
            respond(
                content = """{"storageId":"kg2abc"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = ConvexStorageClient(HttpClient(engine))
        assertEquals("kg2abc", client.upload("https://upload.example/abc", byteArrayOf(1, 2, 3), "text/plain"))
    }

    @Test
    fun downloadsBytes() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(byteArrayOf(4, 5, 6)),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
        val client = ConvexStorageClient(HttpClient(engine))
        assertContentEquals(byteArrayOf(4, 5, 6), client.download("https://files.example/abc"))
    }

    @Test
    fun reportsDownloadErrors() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound, "nope") }
        val client = ConvexStorageClient(HttpClient(engine))
        val failure = assertFailsWith<ConvexStorageException> { client.download("https://files.example/abc") }
        assertEquals(404, failure.statusCode)
    }

    @Test
    fun reportsUploadErrors() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest, "bad") }
        val client = ConvexStorageClient(HttpClient(engine))
        val failure = assertFailsWith<ConvexStorageException> {
            client.upload("https://upload.example/abc", byteArrayOf(1))
        }
        assertEquals(400, failure.statusCode)
    }

    @Test
    fun reportsMalformedUploadBodies() = runTest {
        val engine = MockEngine { respond("this is not json{{{") }
        val client = ConvexStorageClient(HttpClient(engine))
        // A 200 with garbage must surface as a storage failure, not leak the
        // parser's exception type to the caller.
        assertFailsWith<ConvexStorageException> {
            client.upload("https://upload.example/abc", byteArrayOf(1))
        }
    }

    @Test
    fun reportsNonObjectUploadBodies() = runTest {
        val engine = MockEngine { respond("""[1, 2]""") }
        val client = ConvexStorageClient(HttpClient(engine))
        assertFailsWith<ConvexStorageException> {
            client.upload("https://upload.example/abc", byteArrayOf(1))
        }
    }
}
