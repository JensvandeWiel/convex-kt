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

import eu.wynq.convex.core.functions.ConvexFunction
import eu.wynq.convex.core.functions.ConvexFunctionKind
import eu.wynq.convex.core.protocol.ClientMessage
import eu.wynq.convex.core.protocol.ClientMessageJson
import eu.wynq.convex.core.protocol.QuerySetModification
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Covers the descriptor-based entry points. */
@OptIn(ExperimentalCoroutinesApi::class)
class FunctionsTest {

    @Test
    fun subscribesByDescriptor() = runTest {
        val fake = FakeSyncProtocol()
        val client = ConvexSyncClient(SyncProtocolFactory { fake }, backgroundScope)
        client.connect()
        runCurrent()

        assertNotNull(client.subscribe(ConvexFunction("messages:list", ConvexFunctionKind.QUERY)))
        runCurrent()

        val modify = assertIs<ClientMessage.ModifyQuerySet>(ClientMessageJson.decode(fake.sent[1]))
        val add = assertIs<QuerySetModification.Add>(modify.modifications.single())
        assertEquals("messages:list", add.query.udfPath)
    }

    @Test
    fun rejectsAMismatchedKind() = runTest {
        val client = ConvexSyncClient(SyncProtocolFactory { FakeSyncProtocol() }, backgroundScope)
        client.connect()
        runCurrent()

        val mutation = ConvexFunction("messages:send", ConvexFunctionKind.MUTATION)
        assertFailsWith<ConvexClientException> { client.subscribe(mutation) }

        val query = ConvexFunction("messages:list", ConvexFunctionKind.QUERY)
        val failure = runCatching { client.mutate(query, emptyMap<String, ConvexValue>()) }.exceptionOrNull()
        assertIs<ConvexClientException>(failure)
    }
}
