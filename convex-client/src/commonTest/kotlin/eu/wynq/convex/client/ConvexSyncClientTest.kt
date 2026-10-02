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
import eu.wynq.convex.core.protocol.IdentityVersion
import eu.wynq.convex.core.protocol.QueryId
import eu.wynq.convex.core.protocol.QuerySetModification
import eu.wynq.convex.core.protocol.QuerySetVersion
import eu.wynq.convex.core.protocol.ServerMessage
import eu.wynq.convex.core.protocol.ServerMessageJson
import eu.wynq.convex.core.protocol.StateModification
import eu.wynq.convex.core.protocol.StateVersion
import eu.wynq.convex.core.protocol.Timestamp
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the client's outgoing queue and receive loop against an in-memory
 * transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConvexSyncClientTest {

    @Test
    fun connectSendsConnectFirst() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        val connect = assertIs<ClientMessage.Connect>(ClientMessageJson.decode(fake.sent.single()))
        assertEquals(0u, connect.connectionCount)
        assertEquals("InitialConnect", connect.lastCloseReason)
    }

    @Test
    fun subscribeSendsModifyQuerySet() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        client.subscribe("messages:list")
        runCurrent()

        val modify = assertIs<ClientMessage.ModifyQuerySet>(ClientMessageJson.decode(fake.sent[1]))
        val add = assertIs<QuerySetModification.Add>(modify.modifications.single())
        assertEquals("messages:list", add.query.udfPath)
    }

    @Test
    fun transitionUpdatesResults() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()
        val subscriber = assertNotNull(client.subscribe("messages:list"))
        runCurrent()

        fake.push(ServerMessageJson.encode(queryUpdatedTransition(subscriber.queryId)))
        // A tick lets the channel resume the receive coroutine before draining.
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            ConvexResult.Success(ConvexValue.String("v")),
            client.results.value[subscriber.queryId],
        )
    }

    @Test
    fun mutationResolvesOnResponse() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        val result = async { client.mutate("messages:send", mapOf("body" to ConvexValue.String("hi"))) }
        runCurrent()

        val mutation = fake.sent
            .map(ClientMessageJson::decode)
            .filterIsInstance<ClientMessage.Mutation>()
            .single()
        fake.push(
            ServerMessageJson.encode(
                ServerMessage.MutationResponse(
                    requestId = mutation.requestId,
                    result = ConvexResult.Success(ConvexValue.Int64(5)),
                    ts = null,
                    logLines = emptyList(),
                ),
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConvexResult.Success(ConvexValue.Int64(5)), result.await())
    }

    @Test
    fun subscribeBeforeConnectIsRejected() = runTest {
        val client = client(FakeSyncProtocol())
        assertTrue(client.subscribe("messages:list") == null)
    }

    private fun kotlinx.coroutines.test.TestScope.client(fake: SyncProtocol): ConvexSyncClient =
        ConvexSyncClient(SyncProtocolFactory { fake }, backgroundScope)

    private fun queryUpdatedTransition(queryId: QueryId): ServerMessage.Transition = ServerMessage.Transition(
        startVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(0u)),
        endVersion = StateVersion(QuerySetVersion(1u), IdentityVersion(0u), Timestamp(0u)),
        modifications = listOf(
            StateModification.QueryUpdated(queryId, ConvexValue.String("v"), emptyList(), null),
        ),
        clientClockSkew = null,
        serverTs = null,
    )
}
