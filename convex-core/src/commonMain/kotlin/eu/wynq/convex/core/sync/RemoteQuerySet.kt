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

/**
 * The outcome of applying a [ServerMessage.Transition].
 */
public sealed interface TransitionOutcome {
    /**
     * The transition was applied.
     *
     * @property changedQueryIds the queries whose result changed, in the order
     *   the modifications arrived.
     */
    public data class Applied(public val changedQueryIds: List<QueryId>) : TransitionOutcome

    /**
     * The transition does not follow the current version, which means this
     * session missed a message and must reconnect rather than apply it.
     *
     * @property expected the version the client had.
     * @property actual the version the server's transition started from.
     */
    public data class VersionMismatch(
        public val expected: StateVersion,
        public val actual: StateVersion,
    ) : TransitionOutcome
}

/**
 * Tracks the server's query set: its version and the latest result per query.
 *
 * Transitions are applied only when contiguous: a gap means a lost message, and
 * applying it would corrupt the view, so it is reported instead.
 */
public class RemoteQuerySet {
    private var currentVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(0u))
    private val results = mutableMapOf<QueryId, ConvexResult>()
    private val journals = mutableMapOf<QueryId, String?>()

    /** The version the server state is currently at. */
    public val version: StateVersion get() = currentVersion

    /** The latest result for [queryId], or `null` when unknown. */
    public fun result(queryId: QueryId): ConvexResult? = results[queryId]

    /**
     * The pagination journal most recently reported for [queryId], if any.
     *
     * A reconnect resends subscriptions with these journals so paginated queries
     * resume where they left off instead of restarting.
     *
     * @param queryId the query.
     * @return the journal, or `null`.
     */
    public fun journal(queryId: QueryId): String? = journals[queryId]

    /** A snapshot of every known query result. */
    public fun results(): Map<QueryId, ConvexResult> = results.toMap()

    /**
     * Applies a transition if it is contiguous with the current version.
     *
     * @param message the transition to apply.
     * @return [TransitionOutcome.Applied] with the changed queries, or
     *   [TransitionOutcome.VersionMismatch].
     */
    public fun transition(message: ServerMessage.Transition): TransitionOutcome {
        if (message.startVersion != currentVersion) {
            return TransitionOutcome.VersionMismatch(currentVersion, message.startVersion)
        }
        val changed = mutableListOf<QueryId>()
        message.modifications.forEach { modification ->
            when (modification) {
                is StateModification.QueryUpdated -> {
                    results[modification.queryId] = ConvexResult.Success(modification.value)
                    journals[modification.queryId] = modification.journal
                    changed += modification.queryId
                }
                is StateModification.QueryFailed -> {
                    results[modification.queryId] = ConvexResult.Failure(payload(modification))
                    journals[modification.queryId] = modification.journal
                    changed += modification.queryId
                }
                is StateModification.QueryRemoved -> {
                    results.remove(modification.queryId)
                    journals.remove(modification.queryId)
                    changed += modification.queryId
                }
            }
        }
        currentVersion = message.endVersion
        return TransitionOutcome.Applied(changed)
    }

    private fun payload(modification: StateModification.QueryFailed): ErrorPayload =
        // A present payload (even ConvexValue.Null) means a ConvexError; an
        // absent one means an ordinary, possibly redacted, error.
        modification.errorData
            ?.let { ErrorPayload.ErrorData(modification.errorMessage, it) }
            ?: ErrorPayload.Message(modification.errorMessage)
}
