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
package eu.wynq.convex.core.protocol

import eu.wynq.convex.core.sync.RemoteQuerySet
import eu.wynq.convex.core.sync.TransitionOutcome
import eu.wynq.convex.core.value.ConvexValue
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
 * Decodes the committed conformance fixtures with the real codecs.
 *
 * This is what proves the codecs against captured backend traffic rather than
 * against each other. The fixtures are normalized by the recorder (see
 * `conformance/fixtures/query-and-mutation/README.md`), so exact values here are
 * the normalized constants, not live data.
 *
 * The fixture directory is injected by the build as `convexkt.fixtures`.
 */
class ProtocolFixtureTest {

    @Test
    fun decodesCapturedClientFrames() {
        val messages = readFixture("query-and-mutation", "client-to-server").map(ClientMessageJson::decode)

        val sessions = messages.filterIsInstance<ClientMessage.Connect>()
        assertEquals(1, sessions.size)
        assertEquals(0u, sessions.single().connectionCount)
        assertEquals("InitialConnect", sessions.single().lastCloseReason)

        val modifications = messages.filterIsInstance<ClientMessage.ModifyQuerySet>()
        assertEquals(2, modifications.size)
        assertEquals(0u, modifications.first().baseVersion.value)
        assertEquals(2u, modifications.last().newVersion.value)

        val mutation = assertIs<ClientMessage.Mutation>(messages.single { it is ClientMessage.Mutation })
        assertEquals("messages:send", mutation.udfPath)
    }

    @Test
    fun decodesCapturedServerFrames() {
        val messages = readFixture("query-and-mutation", "server-to-client").map(ServerMessageJson::decode)

        val transitions = messages.filterIsInstance<ServerMessage.Transition>()
        assertEquals(3, transitions.size)

        val firstUpdate = transitions.first().modifications.single()
        val updated = assertIs<StateModification.QueryUpdated>(firstUpdate)
        assertEquals(ConvexValue.Array(emptyList()), updated.value)

        val response = assertIs<ServerMessage.MutationResponse>(
            messages.single { it is ServerMessage.MutationResponse },
        )
        assertEquals(CallResult.Success(ConvexValue.Float64(5.0)), response.result)
        // serverTs is a plain number on the wire, StateVersion.ts is base64; both decode.
        assertEquals(Timestamp(0u), transitions.first().serverTs)
        assertEquals(Timestamp(0u), transitions.first().endVersion.ts)

        val removed = assertIs<StateModification.QueryRemoved>(
            transitions.last().modifications.single(),
        )
        assertEquals(0u, removed.queryId.value)
    }

    @Test
    fun replaysCapturedTransitionsThroughTheStateMachine() {
        val remote = RemoteQuerySet()
        val outcomes = readFixture("query-and-mutation", "server-to-client")
            .map(ServerMessageJson::decode)
            .filterIsInstance<ServerMessage.Transition>()
            .map(remote::transition)

        assertTrue(
            outcomes.all { it is TransitionOutcome.Applied },
            "captured transitions must apply contiguously, got $outcomes",
        )
        // The recording ends by unsubscribing, so no results remain.
        assertTrue(remote.results().isEmpty(), "expected no results after QueryRemoved")
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
