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

import eu.wynq.convex.client.generated.GeneratedApi
import eu.wynq.convex.core.ConvexError
import eu.wynq.convex.core.protocol.ClientMessage
import eu.wynq.convex.core.protocol.ClientMessageJson
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.ErrorPayload
import eu.wynq.convex.core.protocol.Query
import eu.wynq.convex.core.protocol.QuerySetModification
import eu.wynq.convex.core.protocol.ServerMessage
import eu.wynq.convex.core.protocol.ServerMessageJson
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the typed call path end to end against an in-memory transport, using the
 * generated descriptors committed from the real backend's apiSpec.
 *
 * The fixture is regenerated from `conformance/fixtures/typed-api/api-spec.json`,
 * so these tests exercise the exact code `convex-codegen` produces for a real
 * deployment, not a hand-written stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedCallsTest {

    @Test
    fun typedMutationEncodesArgumentsAndDecodesTheResult() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        val result = async {
            client.mutate(GeneratedApi.Messages.send, GeneratedApi.Messages.SendRequest(body = "hi"))
        }
        runCurrent()

        val mutation = fake.sent
            .map(ClientMessageJson::decode)
            .filterIsInstance<ClientMessage.Mutation>()
            .single()
        assertEquals("messages:send", mutation.udfPath)
        assertEquals(
            listOf(ConvexValue.Object(mapOf("body" to ConvexValue.String("hi")))),
            mutation.args,
        )

        fake.push(
            ServerMessageJson.encode(
                ServerMessage.MutationResponse(
                    requestId = mutation.requestId,
                    result = ConvexResult.Success(ConvexValue.Float64(2.0)),
                    ts = null,
                    logLines = emptyList(),
                ),
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertEquals(2.0, result.await())
    }

    @Test
    fun typedMutationFailureThrowsConvexError() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        val result = async {
            runCatching {
                client.mutate(GeneratedApi.Messages.send, GeneratedApi.Messages.SendRequest(body = "hi"))
            }
        }
        runCurrent()

        val mutation = fake.sent
            .map(ClientMessageJson::decode)
            .filterIsInstance<ClientMessage.Mutation>()
            .single()
        fake.push(
            ServerMessageJson.encode(
                ServerMessage.MutationResponse(
                    requestId = mutation.requestId,
                    result = ConvexResult.Failure(ErrorPayload.Message("boom")),
                    ts = null,
                    logLines = emptyList(),
                ),
            ),
        )
        runCurrent()
        advanceUntilIdle()

        val error = assertIs<ConvexError>(result.await().exceptionOrNull())
        assertEquals("boom", error.payload.message)
    }

    @Test
    fun typedNoArgQuerySendsTheEmptyArgumentObject() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        client.subscribe(GeneratedApi.Messages.list)
        runCurrent()

        val query = singleAddedQuery(fake)
        assertEquals("messages:list", query.udfPath)
        assertEquals(listOf(ConvexValue.Object(emptyMap())), query.args)
    }

    @Test
    fun typedQueryWithAnArgumentEncodesIt() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        client.subscribe(
            GeneratedApi.Storage.getFileUrl,
            GeneratedApi.Storage.GetFileUrlInput(storageId = "kg2abc"),
        )
        runCurrent()

        val query = singleAddedQuery(fake)
        assertEquals("storage:getFileUrl", query.udfPath)
        assertEquals(
            listOf(ConvexValue.Object(mapOf("storageId" to ConvexValue.String("kg2abc")))),
            query.args,
        )
    }

    @Test
    fun typedActionDecodesItsResult() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        val result = async {
            client.action(GeneratedApi.Messages.echo, GeneratedApi.Messages.EchoRequest(body = "hi"))
        }
        runCurrent()

        val action = fake.sent
            .map(ClientMessageJson::decode)
            .filterIsInstance<ClientMessage.Action>()
            .single()
        assertEquals("messages:echo", action.udfPath)
        assertEquals(
            listOf(ConvexValue.Object(mapOf("body" to ConvexValue.String("hi")))),
            action.args,
        )

        fake.push(
            ServerMessageJson.encode(
                ServerMessage.ActionResponse(
                    requestId = action.requestId,
                    result = ConvexResult.Success(ConvexValue.String("hi")),
                    logLines = emptyList(),
                ),
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertEquals("hi", result.await())
    }

    @Test
    fun typedActionReturnsNullWhenTheServerReturnsNothing() = runTest {
        val fake = FakeSyncProtocol()
        val client = client(fake)
        client.connect()
        runCurrent()

        val result = async {
            client.action(GeneratedApi.Messages.echo, GeneratedApi.Messages.EchoRequest(body = "hi"))
        }
        runCurrent()

        val action = fake.sent
            .map(ClientMessageJson::decode)
            .filterIsInstance<ClientMessage.Action>()
            .single()
        fake.push(
            ServerMessageJson.encode(
                ServerMessage.ActionResponse(
                    requestId = action.requestId,
                    result = ConvexResult.Success(ConvexValue.Null),
                    logLines = emptyList(),
                ),
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertNull(result.await())
    }

    @Test
    fun actionIsNotRetriedOnReconnect() = runTest {
        val protocols = mutableListOf<FakeSyncProtocol>()
        val client = ConvexSyncClient(
            SyncProtocolFactory { FakeSyncProtocol().also { protocols += it } },
            backgroundScope,
        )
        client.connect()
        runCurrent()

        backgroundScope.launch {
            client.action(GeneratedApi.Messages.echo, GeneratedApi.Messages.EchoRequest(body = "hi"))
        }
        runCurrent()
        assertTrue(
            protocols[0].sent.map(ClientMessageJson::decode).any { it is ClientMessage.Action },
            "the action must be sent on the first connection",
        )

        client.reconnect()
        runCurrent()

        assertEquals(2, protocols.size)
        assertTrue(
            protocols[1].sent.map(ClientMessageJson::decode).none { it is ClientMessage.Action },
            "an action must never be retried: a resend could double-execute its side effects",
        )
    }

    private fun singleAddedQuery(fake: FakeSyncProtocol): Query =
        assertIs<QuerySetModification.Add>(
            fake.sent
                .map(ClientMessageJson::decode)
                .filterIsInstance<ClientMessage.ModifyQuerySet>()
                .single()
                .modifications
                .single(),
        ).query

    private fun TestScope.client(fake: SyncProtocol): ConvexSyncClient =
        ConvexSyncClient(SyncProtocolFactory { fake }, backgroundScope)
}
