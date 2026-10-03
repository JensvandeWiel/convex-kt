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

import eu.wynq.convex.core.identity.UserIdentifier
import eu.wynq.convex.core.identity.UserIdentityAttributes
import eu.wynq.convex.core.protocol.AuthenticationToken
import eu.wynq.convex.core.protocol.ClientMessage
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.IdentityVersion
import eu.wynq.convex.core.protocol.QueryId
import eu.wynq.convex.core.protocol.QuerySetModification
import eu.wynq.convex.core.protocol.QuerySetVersion
import eu.wynq.convex.core.protocol.ServerMessage
import eu.wynq.convex.core.protocol.StateModification
import eu.wynq.convex.core.protocol.StateVersion
import eu.wynq.convex.core.protocol.Timestamp
import eu.wynq.convex.core.value.ConvexValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    @Test
    fun resendCarriesTheSuppliedJournal() {
        val state = LocalSyncState()
        val subscription = state.subscribe("messages:list")

        val message = assertIs<ClientMessage.ModifyQuerySet>(
            state.resendQueries { journalFor -> if (journalFor == subscription.subscriberId.queryId) "j1" else null },
        )
        val add = assertIs<QuerySetModification.Add>(message.modifications.single())
        assertEquals("j1", add.query.journal)
    }

    @Test
    fun separateQueriesGetDistinctIds() {
        // Upstream `test_client_separate_queries`: the same path with different
        // args, and a different path, are three independent queries.
        val state = LocalSyncState()
        val first = state.subscribe("getValue1")
        val second = state.subscribe("getValue2")
        val third = state.subscribe("getValue2", mapOf("hello" to ConvexValue.String("world")))

        assertNotEquals(first.subscriberId.queryId, second.subscriberId.queryId)
        assertNotEquals(second.subscriberId.queryId, third.subscriberId.queryId)
        assertEquals(0u, modifyQuerySet(first).baseVersion.value)
        assertEquals(1u, modifyQuerySet(second).baseVersion.value)
        assertEquals(2u, modifyQuerySet(third).baseVersion.value)

        val add = assertIs<QuerySetModification.Add>(modifyQuerySet(third).modifications.single())
        assertEquals("getValue2", add.query.udfPath)
        assertEquals(
            listOf(ConvexValue.Object(mapOf("hello" to ConvexValue.String("world")))),
            add.query.args,
        )
    }

    @Test
    fun identicalQueriesShareIdAndReplayTheLatestResult() {
        // Upstream `test_client_two_identical_queries`: identical subscriptions
        // share one query id and one server-side query, and a later subscriber
        // can read the value already cached for it.
        val state = LocalSyncState()
        val remote = RemoteQuerySet()
        val first = state.subscribe("getValue")
        val second = state.subscribe("getValue")

        assertNull(second.message)
        assertEquals(first.subscriberId.queryId, second.subscriberId.queryId)
        assertNotEquals(first.subscriberId, second.subscriberId)

        val queryId = first.subscriberId.queryId
        remote.transition(queryUpdated(queryId, ConvexValue.Int64(4)))
        assertEquals(ConvexResult.Success(ConvexValue.Int64(4)), remote.result(queryId))

        val third = state.subscribe("getValue")
        assertEquals(queryId, third.subscriberId.queryId)
        assertEquals(ConvexResult.Success(ConvexValue.Int64(4)), remote.result(third.subscriberId.queryId))

        assertNull(state.unsubscribe(first.subscriberId))
        assertNull(state.unsubscribe(second.subscriberId))
        val remove = assertIs<ClientMessage.ModifyQuerySet>(state.unsubscribe(third.subscriberId))
        assertIs<QuerySetModification.Remove>(remove.modifications.single())
    }

    @Test
    fun subscribeUnsubscribeSubscribeDoesNotReuseASubscriberIndex() {
        // Upstream `test_client_subscribe_unsubscribe_subscribe`: a dropped
        // subscriber's index must not be handed to a later one, or handles
        // collide.
        val state = LocalSyncState()
        val first = state.subscribe("getValue1")
        val second = state.subscribe("getValue1")
        assertNull(state.unsubscribe(first.subscriberId))

        val third = state.subscribe("getValue1")
        assertEquals(second.subscriberId.queryId, third.subscriberId.queryId)
        assertNotEquals(second.subscriberId, third.subscriberId)
        assertEquals(2, third.subscriberId.index)
    }

    @Test
    fun authenticateSequenceCoversUserNoneAdminAndImpersonation() {
        // Upstream `test_auth`: every set-auth path sends one Authenticate and
        // advances the identity version.
        val state = LocalSyncState()
        val actingAs = UserIdentityAttributes(
            tokenIdentifier = UserIdentifier.construct("convex", "fake_user"),
            name = "Barbara Liskov",
        )
        val tokens = listOf(
            state.authenticate(AuthenticationToken.User("myauthtoken")),
            state.authenticate(AuthenticationToken.None),
            state.authenticate(AuthenticationToken.Admin("myadminauth")),
            state.authenticate(AuthenticationToken.Admin("myadminauth", actingAs)),
        ).map { assertIs<ClientMessage.Authenticate>(it) }

        assertEquals(AuthenticationToken.User("myauthtoken"), tokens[0].token)
        assertEquals(AuthenticationToken.None, tokens[1].token)
        assertEquals(AuthenticationToken.Admin("myadminauth"), tokens[2].token)
        assertEquals(AuthenticationToken.Admin("myadminauth", actingAs), tokens[3].token)
        assertEquals(listOf(0u, 1u, 2u, 3u), tokens.map { it.baseVersion.value })
    }

    @Test
    fun staticTokenThenClearAdvancesIdentityVersion() {
        // Upstream `test_set_auth_uses_callback_path`: a static token and its
        // clear are ordinary Authenticate messages.
        val state = LocalSyncState()
        val set = assertIs<ClientMessage.Authenticate>(state.authenticate(AuthenticationToken.User("static_token")))
        val clear = assertIs<ClientMessage.Authenticate>(state.authenticate(AuthenticationToken.None))

        assertEquals(AuthenticationToken.User("static_token"), set.token)
        assertEquals(0u, set.baseVersion.value)
        assertEquals(AuthenticationToken.None, clear.token)
        assertEquals(1u, clear.baseVersion.value)
    }

    @Test
    fun cachedResultPersistsWhileASubscriberRemains() {
        // Upstream `test_cached_query_result_persists_while_subscribers_exist`.
        val state = LocalSyncState()
        val remote = RemoteQuerySet()
        val first = state.subscribe("getValue1")
        val second = state.subscribe("getValue1")
        assertNull(second.message)

        remote.transition(queryUpdated(first.subscriberId.queryId, ConvexValue.Int64(10)))
        assertNull(state.unsubscribe(first.subscriberId))

        assertEquals(ConvexResult.Success(ConvexValue.Int64(10)), remote.result(first.subscriberId.queryId))
        assertEquals(
            ConvexResult.Success(ConvexValue.Int64(10)),
            remote.result(second.subscriberId.queryId),
        )
    }

    @Test
    fun transitionPresentsAConsistentViewAcrossSubscribers() {
        // Upstream `test_client_consistent_view_watch`: one transition updates
        // every query atomically, and a query with no update has no result.
        val state = LocalSyncState()
        val remote = RemoteQuerySet()
        val first = state.subscribe("getValue1")
        val secondA = state.subscribe("getValue2")
        val secondB = state.subscribe("getValue2")
        val third = state.subscribe("getValue3")

        remote.transition(
            ServerMessage.Transition(
                startVersion = initialVersion,
                endVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(1u)),
                modifications = listOf(
                    StateModification.QueryUpdated(
                        first.subscriberId.queryId,
                        ConvexValue.Int64(10),
                        emptyList(),
                        null,
                    ),
                    StateModification.QueryUpdated(
                        secondA.subscriberId.queryId,
                        ConvexValue.Int64(20),
                        emptyList(),
                        null,
                    ),
                ),
                clientClockSkew = null,
                serverTs = null,
            ),
        )

        assertEquals(ConvexResult.Success(ConvexValue.Int64(10)), remote.result(first.subscriberId.queryId))
        assertEquals(ConvexResult.Success(ConvexValue.Int64(20)), remote.result(secondA.subscriberId.queryId))
        assertEquals(ConvexResult.Success(ConvexValue.Int64(20)), remote.result(secondB.subscriberId.queryId))
        assertNull(remote.result(third.subscriberId.queryId))
    }

    private fun modifyQuerySet(subscription: Subscription): ClientMessage.ModifyQuerySet =
        assertIs(subscription.message)

    private fun queryUpdated(queryId: QueryId, value: ConvexValue): ServerMessage.Transition =
        ServerMessage.Transition(
            startVersion = initialVersion,
            endVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(1u)),
            modifications = listOf(StateModification.QueryUpdated(queryId, value, emptyList(), null)),
            clientClockSkew = null,
            serverTs = null,
        )

    private val initialVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(0u))
}
