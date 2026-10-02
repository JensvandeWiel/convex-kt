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
package eu.wynq.convex.compose

import eu.wynq.convex.client.ConvexSyncClient
import eu.wynq.convex.client.SyncProtocolFactory
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.ErrorPayload
import eu.wynq.convex.core.protocol.IdentityVersion
import eu.wynq.convex.core.protocol.QueryId
import eu.wynq.convex.core.protocol.QuerySetVersion
import eu.wynq.convex.core.protocol.ServerMessageJson
import eu.wynq.convex.core.protocol.StateModification
import eu.wynq.convex.core.protocol.StateVersion
import eu.wynq.convex.core.protocol.Timestamp
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Covers the pure mapping from results to states. */
class QueryStateTest {

    private val decoder = ConvexDecoder<ConvexValue> { it }

    @Test
    fun mapsMissingResultToLoading() {
        assertEquals(QueryState.Loading, queryStateOf(null, decoder))
    }

    @Test
    fun decodesSuccessWithTheDecoder() {
        val value = ConvexValue.Int64(7)
        val flagged = ConvexDecoder<Long> { raw -> (raw as ConvexValue.Int64).value }
        assertEquals(QueryState.Success(7L), queryStateOf(ConvexResult.Success(value), flagged))
    }

    @Test
    fun mapsFailureToFailure() {
        val error = ErrorPayload.Message("boom")
        assertEquals(QueryState.Failure(error), queryStateOf(ConvexResult.Failure(error), decoder))
    }
}

/** Covers subscribing, the initial state, and reacting to a transition. */
@OptIn(ExperimentalCoroutinesApi::class)
class QueryControllerTest {

    @Test
    fun subscribesAndTracksResults() = runTest {
        val fake = FakeSyncProtocol()
        val client = ConvexSyncClient(SyncProtocolFactory { fake }, backgroundScope)
        client.connect()
        runCurrent()

        val controller = QueryController(client, "messages:list", decoder = ConvexDecoder { it })
        runCurrent()
        val queryId = assertNotNull(controller.queryId)
        assertEquals(QueryState.Loading, controller.state.first())

        fake.push(queryUpdated(queryId, ConvexValue.String("hi")))
        runCurrent()
        advanceUntilIdle()

        assertEquals(QueryState.Success(ConvexValue.String("hi")), controller.state.first())
        controller.close()
    }

    private fun queryUpdated(queryId: QueryId, value: ConvexValue) = ServerMessageJson.encode(
        eu.wynq.convex.core.protocol.ServerMessage.Transition(
            startVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(0u)),
            endVersion = StateVersion(QuerySetVersion(1u), IdentityVersion(0u), Timestamp(0u)),
            modifications = listOf(StateModification.QueryUpdated(queryId, value, emptyList(), null)),
            clientClockSkew = null,
            serverTs = null,
        ),
    )
}
