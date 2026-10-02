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

import eu.wynq.convex.core.protocol.AuthenticationToken
import eu.wynq.convex.core.protocol.ClientMessage
import eu.wynq.convex.core.protocol.ClientMessageJson
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.IdentityVersion
import eu.wynq.convex.core.protocol.QueryId
import eu.wynq.convex.core.protocol.QuerySetModification
import eu.wynq.convex.core.protocol.QuerySetVersion
import eu.wynq.convex.core.protocol.RequestId
import eu.wynq.convex.core.protocol.ServerMessage
import eu.wynq.convex.core.protocol.ServerMessageJson
import eu.wynq.convex.core.protocol.StateModification
import eu.wynq.convex.core.protocol.StateVersion
import eu.wynq.convex.core.protocol.Timestamp
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        val subscriber = client.subscribe("messages:list")
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
    fun subscriptionRequestedBeforeConnectIsSentOnConnect() = runTest {
        val fake = FakeSyncProtocol()
        val client = ConvexSyncClient(SyncProtocolFactory { fake }, backgroundScope)
        // Requested before connecting; it must still be established.
        client.subscribe("messages:list")
        client.connect()
        runCurrent()

        val decoded = fake.sent.map(ClientMessageJson::decode)
        assertIs<ClientMessage.Connect>(decoded.first())
        val modify = assertIs<ClientMessage.ModifyQuerySet>(decoded[1])
        val add = assertIs<QuerySetModification.Add>(modify.modifications.single())
        assertEquals("messages:list", add.query.udfPath)
    }

    @Test
    fun authFetcherIsSentAfterConnect() = runTest {
        val fake = FakeSyncProtocol()
        val client = ConvexSyncClient(
            SyncProtocolFactory { fake },
            backgroundScope,
            authFetcher = AuthTokenFetcher { AuthenticationToken.User("jwt") },
        )
        client.connect()
        runCurrent()

        val decoded = fake.sent.map(ClientMessageJson::decode)
        assertIs<ClientMessage.Connect>(decoded[0])
        val authenticate = assertIs<ClientMessage.Authenticate>(decoded[1])
        assertEquals(AuthenticationToken.User("jwt"), authenticate.token)
        assertEquals(0u, authenticate.baseVersion.value)
    }

    @Test
    fun authFetcherRefreshIsSkippedForNone() = runTest {
        val fake = FakeSyncProtocol()
        val client = ConvexSyncClient(
            SyncProtocolFactory { fake },
            backgroundScope,
            authFetcher = AuthTokenFetcher { AuthenticationToken.None },
        )
        client.connect()
        runCurrent()

        assertTrue(fake.sent.map(ClientMessageJson::decode).none { it is ClientMessage.Authenticate })
    }

    @Test
    fun reconnectResendsQueriesFromVersionZero() = runTest {
        val fake = FakeSyncProtocol()
        var forceRefresh = false
        val client = ConvexSyncClient(
            SyncProtocolFactory { fake },
            backgroundScope,
            authFetcher = AuthTokenFetcher { force ->
                forceRefresh = force
                AuthenticationToken.User("jwt")
            },
        )
        client.connect()
        runCurrent()
        client.subscribe("messages:list")
        runCurrent()
        fake.sent.clear()

        client.reconnect()
        runCurrent()

        val decoded = fake.sent.map(ClientMessageJson::decode)
        assertIs<ClientMessage.Connect>(decoded.first())
        val modify = decoded.filterIsInstance<ClientMessage.ModifyQuerySet>().single()
        assertEquals(0u, modify.baseVersion.value)
        assertIs<QuerySetModification.Add>(modify.modifications.single())
        assertTrue(forceRefresh, "reconnect must force a token refresh")
    }

    @Test
    fun automaticallyReconnectsAfterConnectionLoss() = runTest {
        val protocols = mutableListOf<FakeSyncProtocol>()
        val factory = SyncProtocolFactory { FakeSyncProtocol().also { protocols += it } }
        val client = ConvexSyncClient(
            factory,
            backgroundScope,
            reconnectPolicy = ReconnectPolicy(initialDelayMillis = 100),
        )
        client.connect()
        runCurrent()
        assertEquals(1, protocols.size)

        protocols[0].closeFromServer()
        runCurrent()
        advanceTimeBy(150)
        runCurrent()

        assertEquals(2, protocols.size, "expected a replacement connection")
        assertTrue(
            protocols[1].sent.map(ClientMessageJson::decode).any { it is ClientMessage.Connect },
            "the replacement connection must send Connect",
        )
    }

    @Test
    fun callTimesOutWithoutAResponse() = runTest {
        val fake = FakeSyncProtocol()
        val client = ConvexSyncClient(
            SyncProtocolFactory { fake },
            backgroundScope,
            callTimeoutMillis = 1_000,
        )
        client.connect()
        runCurrent()

        val failure = runCatching { client.mutate("messages:send", emptyMap()) }.exceptionOrNull()
        assertIs<ConvexClientException>(failure)
    }

    @Test
    fun surfacesAuthErrors() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        val received = async { client.authErrors.first() }
        runCurrent()
        fake.push(ServerMessageJson.encode(ServerMessage.AuthError("expired", null, null)))
        runCurrent()
        advanceUntilIdle()

        assertEquals("expired", received.await())
    }

    @Test
    fun reassemblesChunkedTransitions() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()
        val subscriber = client.subscribe("messages:list")
        runCurrent()

        val full = ServerMessageJson.encode(queryUpdatedTransition(subscriber.queryId, ConvexValue.String("chunked")))
        val half = full.length / 2
        fake.push(ServerMessageJson.encode(ServerMessage.TransitionChunk(full.substring(0, half), 0u, 2u, "t1")))
        fake.push(ServerMessageJson.encode(ServerMessage.TransitionChunk(full.substring(half), 1u, 2u, "t1")))
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            ConvexResult.Success(ConvexValue.String("chunked")),
            client.results.value[subscriber.queryId],
        )
    }

    @Test
    fun optimisticUpdateShowsUntilTheNextTransition() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()
        val subscriber = client.subscribe("messages:list")
        runCurrent()
        val queryId = subscriber.queryId

        val mutation = async {
            client.mutate("messages:send", mapOf("body" to ConvexValue.String("hi"))) { results ->
                results + (queryId to ConvexResult.Success(ConvexValue.String("optimistic")))
            }
        }
        runCurrent()
        assertEquals(
            ConvexResult.Success(ConvexValue.String("optimistic")),
            client.results.value[queryId],
        )

        fake.push(ServerMessageJson.encode(queryUpdatedTransition(queryId, ConvexValue.String("server"))))
        runCurrent()
        fake.push(
            ServerMessageJson.encode(
                ServerMessage.MutationResponse(
                    requestId = RequestId(0u),
                    result = ConvexResult.Success(ConvexValue.Int64(1)),
                    ts = null,
                    logLines = emptyList(),
                ),
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConvexResult.Success(ConvexValue.String("server")), client.results.value[queryId])
        assertEquals(ConvexResult.Success(ConvexValue.Int64(1)), mutation.await())
    }

    private fun kotlinx.coroutines.test.TestScope.client(fake: SyncProtocol): ConvexSyncClient =
        ConvexSyncClient(SyncProtocolFactory { fake }, backgroundScope)

    private fun queryUpdatedTransition(
        queryId: QueryId,
        value: ConvexValue = ConvexValue.String("v"),
    ): ServerMessage.Transition = ServerMessage.Transition(
        startVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(0u)),
        endVersion = StateVersion(QuerySetVersion(1u), IdentityVersion(0u), Timestamp(0u)),
        modifications = listOf(
            StateModification.QueryUpdated(queryId, value, emptyList(), null),
        ),
        clientClockSkew = null,
        serverTs = null,
    )
}
