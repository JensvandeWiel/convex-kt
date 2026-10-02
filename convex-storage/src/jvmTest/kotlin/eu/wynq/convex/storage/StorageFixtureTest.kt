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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replays the recorded storage exchange through the client, so response parsing
 * is proven against what the backend actually returns rather than against
 * hand-written JSON. The fixture directory is injected as `convexkt.fixtures`.
 */
class StorageFixtureTest {

    @Test
    fun replaysRecordedUploadAndDownload() = runTest {
        val fixture = readFixture()
        val storageId = fixture.getValue("uploadResponseBody").jsonObject
            .getValue("storageId").jsonPrimitive.content
        val payload = fixture.getValue("payloadUtf8").jsonPrimitive.content.encodeToByteArray()
        assertTrue(fixture.getValue("fileUrlIsAbsolute").jsonPrimitive.content.toBoolean())

        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond(
                    content = fixture.getValue("uploadResponseBody").toString(),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    content = ByteReadChannel(payload),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                )
            }
        }
        val client = ConvexStorageClient(HttpClient(engine))

        assertEquals(storageId, client.upload("https://upload.example/abc", payload))
        assertContentEquals(payload, client.download("https://files.example/abc"))
    }

    private fun readFixture(): kotlinx.serialization.json.JsonObject {
        val root = assertNotNull(
            System.getProperty("convexkt.fixtures"),
            "convexkt.fixtures is not set; run via Gradle",
        )
        val file = File(root, "storage/exchange.json")
        assertNotNull(file.takeIf { it.isFile }, "missing fixture: ${file.path}")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }
}
