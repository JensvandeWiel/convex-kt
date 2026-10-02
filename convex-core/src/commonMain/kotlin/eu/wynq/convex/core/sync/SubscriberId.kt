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

import eu.wynq.convex.core.protocol.QueryId

/**
 * Identifies a single subscriber to a query.
 *
 * Two subscribers of the same query share a [queryId] but have distinct
 * [index] values. This is what lets the machine tell "one of two subscribers
 * left" (no message) from "the last subscriber left" (remove the query).
 *
 * The constructor is internal: callers receive ids from
 * [LocalSyncState.subscribe] and hand them back to
 * [LocalSyncState.unsubscribe].
 *
 * @property queryId the query being subscribed to.
 * @property index a unique, never-reused index within that query.
 */
public class SubscriberId internal constructor(
    public val queryId: QueryId,
    internal val index: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is SubscriberId && other.queryId == queryId && other.index == index)

    override fun hashCode(): Int = HASH_SEED * queryId.value.hashCode() + index

    override fun toString(): String = "SubscriberId($queryId, $index)"

    private companion object {
        private const val HASH_SEED = 31
    }
}
