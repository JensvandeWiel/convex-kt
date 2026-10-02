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

import eu.wynq.convex.core.protocol.ClientMessage
import eu.wynq.convex.core.protocol.ClientMessageJson
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.QuerySetModification
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives [ConvexSyncClient] with the recorded query-and-mutation traffic.
 *
 * The captured server frames are replayed through the client while it performs
 * the same subscription and mutation, so the client is exercised end to end
 * against bytes the backend actually sent. The fixture directory is injected by
 * the build as `convexkt.fixtures`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FixtureReplayTest {

    @Test
    fun replaysCapturedQueryAndMutation() = runTest {
        val fake = FakeSyncProtocol()
        val client = ConvexSyncClient(SyncProtocolFactory { fake }, backgroundScope)
        client.connect()
        runCurrent()

        assertNotNull(client.subscribe("messages:list"))
        runCurrent()
        val mutation = async { client.mutate("messages:send", mapOf("body" to ConvexValue.String("hello"))) }
        runCurrent()

        readFixture("query-and-mutation", "server-to-client").forEach(fake::push)
        runCurrent()
        advanceUntilIdle()

        // The recording ends by unsubscribing, so no results remain.
        assertTrue(client.results.value.isEmpty(), "expected no results after QueryRemoved")
        assertEquals(ConvexResult.Success(ConvexValue.Float64(5.0)), mutation.await())

        val sent = fake.sent.map(ClientMessageJson::decode)
        val add = assertIs<QuerySetModification.Add>(
            sent.filterIsInstance<ClientMessage.ModifyQuerySet>()
                .first()
                .modifications
                .single(),
        )
        assertEquals("messages:list", add.query.udfPath)
        val call = assertIs<ClientMessage.Mutation>(sent.filterIsInstance<ClientMessage.Mutation>().single())
        assertEquals("messages:send", call.udfPath)
    }

    private fun readFixture(scenario: String, direction: String): List<String> {
        val root = assertNotNull(
            System.getProperty("convexkt.fixtures"),
            "convexkt.fixtures is not set; run via Gradle",
        )
        val file = File(root, "$scenario/$direction.ndjson")
        assertNotNull(file.takeIf { it.isFile }, "missing fixture: ${file.path}")
        return file.readLines().filter { it.isNotBlank() }.map { line ->
            Json.parseToJsonElement(line).jsonObject.getValue("data").jsonPrimitive.content
        }
    }
}
