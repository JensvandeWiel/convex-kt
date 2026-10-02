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

import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.ErrorPayload
import eu.wynq.convex.core.protocol.IdentityVersion
import eu.wynq.convex.core.protocol.QueryId
import eu.wynq.convex.core.protocol.QuerySetVersion
import eu.wynq.convex.core.protocol.ServerMessage
import eu.wynq.convex.core.protocol.StateModification
import eu.wynq.convex.core.protocol.StateVersion
import eu.wynq.convex.core.protocol.Timestamp
import eu.wynq.convex.core.value.ConvexValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers applying server transitions: result tracking, version continuity, and
 * the error-payload mapping.
 */
class RemoteQuerySetTest {

    private val initial = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(0u))

    @Test
    fun appliesQueryUpdatedAndAdvancesVersion() {
        val remote = RemoteQuerySet()
        val outcome = remote.transition(
            transition(
                start = initial,
                end = version(1u),
                modifications = listOf(
                    StateModification.QueryUpdated(
                        queryId = QueryId(0u),
                        value = ConvexValue.Int64(5),
                        logLines = emptyList(),
                        journal = null,
                    ),
                ),
            ),
        )

        assertEquals(TransitionOutcome.Applied(listOf(QueryId(0u))), outcome)
        assertEquals(ConvexResult.Success(ConvexValue.Int64(5)), remote.result(QueryId(0u)))
        assertEquals(version(1u), remote.version)
    }

    @Test
    fun detectsAVersionGap() {
        val remote = RemoteQuerySet()
        val outcome = assertIs<TransitionOutcome.VersionMismatch>(
            remote.transition(transition(version(5u), version(6u), emptyList())),
        )
        assertEquals(initial, outcome.expected)
        assertEquals(version(5u), outcome.actual)
        // A mismatch must not mutate state.
        assertEquals(initial, remote.version)
    }

    @Test
    fun queryFailedWithoutPayloadIsAnOrdinaryError() {
        val remote = RemoteQuerySet()
        remote.transition(
            transition(
                start = initial,
                end = version(1u),
                modifications = listOf(
                    StateModification.QueryFailed(QueryId(1u), "boom", emptyList(), null, errorData = null),
                ),
            ),
        )
        val failure = assertIs<ConvexResult.Failure>(remote.result(QueryId(1u)))
        assertEquals(ErrorPayload.Message("boom"), failure.error)
    }

    @Test
    fun queryFailedWithPresentButNullPayloadIsAConvexError() {
        val remote = RemoteQuerySet()
        remote.transition(
            transition(
                start = initial,
                end = version(1u),
                modifications = listOf(
                    StateModification.QueryFailed(QueryId(1u), "boom", emptyList(), null, errorData = ConvexValue.Null),
                ),
            ),
        )
        val failure = assertIs<ConvexResult.Failure>(remote.result(QueryId(1u)))
        assertEquals(ErrorPayload.ErrorData("boom", ConvexValue.Null), failure.error)
    }

    @Test
    fun queryRemovedDropsTheResult() {
        val remote = RemoteQuerySet()
        remote.transition(
            transition(
                start = initial,
                end = version(1u),
                modifications = listOf(
                    StateModification.QueryUpdated(QueryId(0u), ConvexValue.Null, emptyList(), null),
                ),
            ),
        )
        remote.transition(
            transition(
                start = version(1u),
                end = version(2u),
                modifications = listOf(StateModification.QueryRemoved(QueryId(0u))),
            ),
        )
        assertNull(remote.result(QueryId(0u)))
    }

    private fun version(querySet: UInt): StateVersion =
        StateVersion(QuerySetVersion(querySet), IdentityVersion(0u), Timestamp(0u))

    private fun transition(
        start: StateVersion,
        end: StateVersion,
        modifications: List<StateModification>,
    ): ServerMessage.Transition = ServerMessage.Transition(
        startVersion = start,
        endVersion = end,
        modifications = modifications,
        clientClockSkew = null,
        serverTs = null,
    )
}
