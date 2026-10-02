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
package eu.wynq.convex.core.sync

import eu.wynq.convex.core.protocol.AuthenticationToken
import eu.wynq.convex.core.protocol.ClientMessage
import eu.wynq.convex.core.protocol.QuerySetModification
import eu.wynq.convex.core.value.ConvexValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers the local subscription reducer: id allocation and reuse, subscriber
 * indexing, and the "last subscriber removes the query" rule.
 */
class LocalSyncStateTest {

    @Test
    fun firstSubscribeEmitsAddAndAdvancesVersion() {
        val state = LocalSyncState()
        val subscription = state.subscribe("messages:list")

        val message = assertIs<ClientMessage.ModifyQuerySet>(subscription.message)
        assertEquals(0u, message.baseVersion.value)
        assertEquals(1u, message.newVersion.value)
        val add = assertIs<QuerySetModification.Add>(message.modifications.single())
        assertEquals("messages:list", add.query.udfPath)
        assertEquals(0u, add.query.queryId.value)
        assertEquals(1, state.queryCount)
    }

    @Test
    fun identicalSubscribeReusesQueryIdAndEmitsNothing() {
        val state = LocalSyncState()
        val first = state.subscribe("messages:list", mapOf("room" to ConvexValue.String("a")))
        val second = state.subscribe("messages:list", mapOf("room" to ConvexValue.String("a")))

        assertNull(second.message)
        assertEquals(first.subscriberId.queryId, second.subscriberId.queryId)
        assertEquals(1, state.queryCount)
    }

    @Test
    fun argumentOrderDoesNotCreateDuplicateQueries() {
        val state = LocalSyncState()
        state.subscribe("q", linkedMapOf("a" to ConvexValue.Int64(1), "b" to ConvexValue.Int64(2)))
        val second = state.subscribe("q", linkedMapOf("b" to ConvexValue.Int64(2), "a" to ConvexValue.Int64(1)))

        assertNull(second.message)
        assertEquals(1, state.queryCount)
    }

    @Test
    fun secondSubscriberDoesNotEmitAndOnlyLastUnsubscribeRemoves() {
        val state = LocalSyncState()
        val first = state.subscribe("messages:list")
        val second = state.subscribe("messages:list")

        assertNull(second.message)
        assertNull(state.unsubscribe(first.subscriberId))

        val removeMessage = assertIs<ClientMessage.ModifyQuerySet>(state.unsubscribe(second.subscriberId))
        val remove = assertIs<QuerySetModification.Remove>(removeMessage.modifications.single())
        assertEquals(first.subscriberId.queryId, remove.queryId)
        assertEquals(0, state.queryCount)
    }

    @Test
    fun doubleUnsubscribeIsANoOp() {
        val state = LocalSyncState()
        val subscription = state.subscribe("q")
        state.unsubscribe(subscription.subscriberId)
        assertNull(state.unsubscribe(subscription.subscriberId))
    }

    @Test
    fun authenticateAdvancesIdentityVersion() {
        val state = LocalSyncState()
        val first = assertIs<ClientMessage.Authenticate>(state.authenticate(AuthenticationToken.None))
        val second = assertIs<ClientMessage.Authenticate>(state.authenticate(AuthenticationToken.User("jwt")))

        assertEquals(0u, first.baseVersion.value)
        assertEquals(1u, second.baseVersion.value)
    }

    @Test
    fun resendReaddsEveryQueryFromVersionZero() {
        val state = LocalSyncState()
        state.subscribe("a")
        state.subscribe("b")

        val message = assertIs<ClientMessage.ModifyQuerySet>(state.resendQueries())
        assertEquals(0u, message.baseVersion.value)
        assertEquals(1u, message.newVersion.value)
        assertEquals(2, message.modifications.size)
    }
}
