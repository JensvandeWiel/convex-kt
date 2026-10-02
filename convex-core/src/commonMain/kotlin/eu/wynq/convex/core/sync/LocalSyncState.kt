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
import eu.wynq.convex.core.protocol.IdentityVersion
import eu.wynq.convex.core.protocol.Query
import eu.wynq.convex.core.protocol.QueryId
import eu.wynq.convex.core.protocol.QuerySetModification
import eu.wynq.convex.core.protocol.QuerySetVersion
import eu.wynq.convex.core.value.ConvexJson
import eu.wynq.convex.core.value.ConvexValue
import kotlin.jvm.JvmInline

/**
 * The result of [LocalSyncState.subscribe].
 *
 * @property message the message to send, or `null` when an identical
 *   subscription already existed and the server needs no change.
 * @property subscriberId the handle to pass back to unsubscribe.
 */
public data class Subscription(
    public val message: ClientMessage?,
    public val subscriberId: SubscriberId,
)

/**
 * A canonical identity for a subscription: the function path plus its
 * arguments. Two subscribe calls with the same token share one query id.
 */
@JvmInline
internal value class QueryToken(private val canonical: String) {
    override fun toString(): String = canonical
}

/** A locally tracked subscription. [subscribers] and [index] are mutable bookkeeping. */
internal class LocalQuery(
    val id: QueryId,
    val udfPath: String,
    val args: Map<String, ConvexValue>,
    var subscribers: Int,
    var index: Int,
)

/**
 * Reduces subscription intents into the `ClientMessage`s that change the
 * server's query set, and allocates query ids.
 *
 * This is the transport-agnostic half of the client: it never performs I/O and
 * never blocks, so it can be driven by a WebSocket, a test, or a replay of the
 * recorded conformance fixtures.
 */
public class LocalSyncState {
    private var nextQueryId = QueryId(0u)
    private var querySetVersion = QuerySetVersion(0u)
    private var identityVersion = IdentityVersion(0u)
    private val querySet = mutableMapOf<QueryToken, LocalQuery>()
    private val queryIdToToken = mutableMapOf<QueryId, QueryToken>()

    /** The number of distinct queries currently subscribed to. */
    public val queryCount: Int get() = querySet.size

    /**
     * Subscribes to a query, reusing an existing query id when an identical
     * subscription already exists.
     *
     * @param udfPath the function path, for example `messages:list`.
     * @param args the single argument object.
     * @return the message to send (when the server's query set must change) and
     *   the subscriber handle.
     */
    public fun subscribe(
        udfPath: String,
        args: Map<String, ConvexValue> = emptyMap(),
    ): Subscription {
        val token = tokenFor(udfPath, args)
        val existing = querySet[token]
        if (existing != null) {
            existing.subscribers += 1
            existing.index += 1
            return Subscription(null, SubscriberId(existing.id, existing.index))
        }

        val queryId = nextQueryId
        nextQueryId = QueryId(nextQueryId.value + 1u)
        val baseVersion = querySetVersion
        val newVersion = QuerySetVersion(baseVersion.value + 1u)
        querySetVersion = newVersion

        val message = ClientMessage.ModifyQuerySet(
            baseVersion = baseVersion,
            newVersion = newVersion,
            modifications = listOf(
                QuerySetModification.Add(Query(queryId, udfPath, listOf(ConvexValue.Object(args)))),
            ),
        )
        querySet[token] = LocalQuery(queryId, udfPath, args, subscribers = 1, index = 0)
        queryIdToToken[queryId] = token
        return Subscription(message, SubscriberId(queryId, 0))
    }

    /**
     * Removes a subscriber.
     *
     * @param subscriberId a handle from [subscribe].
     * @return a remove message when this was the last subscriber, otherwise
     *   `null`; also `null` for an unknown handle, so a double unsubscribe is a
     *   no-op rather than a crash.
     */
    public fun unsubscribe(subscriberId: SubscriberId): ClientMessage? {
        val token = queryIdToToken[subscriberId.queryId] ?: return null
        val local = querySet[token] ?: return null
        if (local.subscribers > 1) {
            local.subscribers -= 1
            return null
        }
        querySet.remove(token)
        queryIdToToken.remove(subscriberId.queryId)
        val baseVersion = querySetVersion
        val newVersion = QuerySetVersion(baseVersion.value + 1u)
        querySetVersion = newVersion
        return ClientMessage.ModifyQuerySet(
            baseVersion = baseVersion,
            newVersion = newVersion,
            modifications = listOf(QuerySetModification.Remove(subscriberId.queryId)),
        )
    }

    /**
     * Resets the identity version after a reconnect.
     *
     * The server forgets the previous connection's identity version, so the
     * next [authenticate] must start from zero again.
     */
    public fun resetIdentityVersion() {
        identityVersion = IdentityVersion(0u)
    }

    /**
     * Builds an authentication message and advances the identity version.
     *
     * @param token the new authentication state.
     * @return the message to send.
     */
    public fun authenticate(token: AuthenticationToken): ClientMessage {
        val baseVersion = identityVersion
        identityVersion = IdentityVersion(identityVersion.value + 1u)
        return ClientMessage.Authenticate(baseVersion, token)
    }

    /**
     * Re-adds every subscription after a reconnect.
     *
     * The query-set version is reset to zero, matching the server, which has
     * forgotten the previous session.
     *
     * @return the message that restores all subscriptions.
     */
    public fun resendQueries(): ClientMessage {
        val modifications = querySet.values.map { local ->
            QuerySetModification.Add(Query(local.id, local.udfPath, listOf(ConvexValue.Object(local.args))))
        }
        querySetVersion = QuerySetVersion(0u)
        val newVersion = QuerySetVersion(1u)
        querySetVersion = newVersion
        return ClientMessage.ModifyQuerySet(QuerySetVersion(0u), newVersion, modifications)
    }

    private fun tokenFor(udfPath: String, args: Map<String, ConvexValue>): QueryToken {
        // A canonical JSON string keeps the token stable and readable; the
        // codec sorts object keys, so argument order cannot create duplicates.
        val canonical = ConvexJson.encode(
            ConvexValue.Object(
                mapOf(
                    "args" to ConvexValue.Array(listOf(ConvexValue.Object(args))),
                    "udfPath" to ConvexValue.String(udfPath),
                ),
            ),
        )
        return QueryToken(canonical)
    }
}
