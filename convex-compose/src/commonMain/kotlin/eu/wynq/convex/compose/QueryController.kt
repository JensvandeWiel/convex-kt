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
import eu.wynq.convex.core.protocol.QueryId
import eu.wynq.convex.core.sync.SubscriberId
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Subscribes to a query and exposes its [QueryState] as a cold [Flow].
 *
 * Creating the controller subscribes; [close] unsubscribes. The flow is derived
 * from the client's shared results, so several controllers observing the same
 * query share one server subscription.
 *
 * @param T the decoded value type.
 * @property client the sync client.
 * @property decoder converts raw values to `T`.
 */
public class QueryController<T>(
    private val client: ConvexSyncClient,
    udfPath: String,
    args: Map<String, ConvexValue> = emptyMap(),
    private val decoder: ConvexDecoder<T>,
) {
    private val subscriberId: SubscriberId? = client.subscribe(udfPath, args)
    private val subscriberQueryId: QueryId? = subscriberId?.queryId

    /** The server query id this controller observes, or `null` when not connected. */
    public val queryId: QueryId? get() = subscriberQueryId

    /** The query's state as it changes over time. */
    public val state: Flow<QueryState<T>> = client.results.map { results ->
        queryStateOf(subscriberQueryId?.let(results::get), decoder)
    }

    /** Unsubscribes from the query. */
    public fun close() {
        subscriberId?.let(client::unsubscribe)
    }
}
