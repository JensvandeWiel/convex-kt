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
import kotlin.test.assertTrue

/**
 * Covers the storage HTTP client against a mock engine, so request shapes and
 * response parsing are checked without a live backend.
 */
class ConvexStorageClientTest {

    @Test
    fun generatesUploadUrlWithAuthHeader() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/storage/generate-upload-url", request.url.encodedPath)
            assertEquals("Convex admin-key", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"url":"https://upload.example/abc"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = ConvexStorageClient("https://example.convex.cloud", HttpClient(engine), "admin-key")
        assertEquals("https://upload.example/abc", client.generateUploadUrl())
    }

    @Test
    fun uploadsBytesAndReadsStorageId() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://upload.example/abc", request.url.toString())
            respond(
                content = """{"storageId":"kg2abc"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = ConvexStorageClient("https://example.convex.cloud", HttpClient(engine))
        assertEquals("kg2abc", client.upload("https://upload.example/abc", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun uploadFileGeneratesThenUploads() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            val body = if (request.url.encodedPath.endsWith("generate-upload-url")) {
                """{"url":"https://upload.example/abc"}"""
            } else {
                """{"storageId":"kg2abc"}"""
            }
            respond(content = body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = ConvexStorageClient("https://example.convex.cloud", HttpClient(engine))
        assertEquals("kg2abc", client.uploadFile(byteArrayOf(9)))
        assertEquals(listOf("/api/storage/generate-upload-url", "/abc"), paths)
    }

    @Test
    fun downloadsBytes() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(byteArrayOf(4, 5, 6)),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
        val client = ConvexStorageClient("https://example.convex.cloud", HttpClient(engine))
        assertContentEquals(byteArrayOf(4, 5, 6), client.download("kg2abc"))
    }

    @Test
    fun reportsErrors() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound, "nope") }
        val client = ConvexStorageClient("https://example.convex.cloud", HttpClient(engine))
        val failure = assertFailsWith<ConvexStorageException> { client.download("kg2abc") }
        assertEquals(404, failure.statusCode)
    }

    @Test
    fun buildsFileUrl() = runTest {
        val client = ConvexStorageClient("https://example.convex.cloud/", HttpClient(MockEngine { respond("") }))
        assertEquals("https://example.convex.cloud/api/storage/kg2abc", client.fileUrl("kg2abc"))
        assertTrue(client.fileUrl("x").endsWith("/api/storage/x"))
    }
}
