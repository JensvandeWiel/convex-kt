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
package eu.wynq.convex.core.protocol

import eu.wynq.convex.core.value.ConvexValue

/**
 * A subscription to a Convex query within a session.
 *
 * @property queryId the handle the client uses in later versions.
 * @property udfPath the function path, for example `messages:list`.
 * @property args the serialized arguments.
 * @property journal an opaque pagination token from a previous result.
 * @property componentPath an optional component to call within, for the
 *   dashboard.
 */
public data class Query(
    public val queryId: QueryId,
    public val udfPath: String,
    public val args: List<ConvexValue> = emptyList(),
    public val journal: String? = null,
    public val componentPath: String? = null,
)

/**
 * A single change to a session's query set.
 *
 * Closed on purpose: the backend only accepts `Add` and `Remove`, and a new
 * modification kind must break compilation here rather than be dropped.
 */
public sealed interface QuerySetModification {
    /**
     * Adds a query to the set.
     *
     * @property query the query to subscribe to.
     */
    public data class Add(public val query: Query) : QuerySetModification

    /**
     * Removes a query from the set.
     *
     * @property queryId the query to unsubscribe from.
     */
    public data class Remove(public val queryId: QueryId) : QuerySetModification
}

/**
 * The authentication state sent with [ClientMessage.Authenticate].
 *
 * The variants mirror the upstream `AuthenticationToken`; `None` is a
 * logged-out session rather than an absence of the message.
 */
public sealed interface AuthenticationToken {
    /**
     * An admin key, optionally impersonating a user.
     *
     * @property value the admin key.
     * @property actingAs the identity attributes to impersonate, if any.
     */
    public data class Admin(
        public val value: String,
        public val actingAs: ConvexValue? = null,
    ) : AuthenticationToken

    /**
     * An OpenID Connect JWT.
     *
     * @property value the token.
     */
    public data class User(public val value: String) : AuthenticationToken

    /** A logged-out session. */
    public data object None : AuthenticationToken
}

/**
 * A message the client sends to the sync WebSocket.
 *
 * The hierarchy is closed so the JSON encoder must handle every variant. Wire
 * field names are camelCase and the tag is the `type` field, matching the
 * upstream serializer.
 */
public sealed interface ClientMessage {
    /**
     * Opens a session.
     *
     * `lastCloseReason` is always sent; `maxObservedTimestamp` and `clientTs`
     * are omitted when absent, matching the upstream serializer.
     *
     * @property sessionId the UUID assigned by the client; the backend requires
     *   a well-formed UUID.
     * @property connectionCount how many times this client has connected.
     * @property lastCloseReason why the previous connection ended.
     * @property maxObservedTimestamp the newest server timestamp already seen.
     * @property clientTs the client's wall clock, if it has one.
     */
    public data class Connect(
        public val sessionId: SessionId,
        public val connectionCount: UInt,
        public val lastCloseReason: String,
        public val maxObservedTimestamp: Timestamp? = null,
        public val clientTs: Long? = null,
    ) : ClientMessage

    /**
     * Applies a batch of subscription changes at a new query-set version.
     *
     * @property baseVersion the version the changes apply to.
     * @property newVersion the version after the changes.
     * @property modifications the add/remove operations.
     */
    public data class ModifyQuerySet(
        public val baseVersion: QuerySetVersion,
        public val newVersion: QuerySetVersion,
        public val modifications: List<QuerySetModification>,
    ) : ClientMessage

    /**
     * Invokes a mutation.
     *
     * @property requestId correlates the response.
     * @property udfPath the function path.
     * @property args the arguments.
     * @property componentPath an optional component to call within.
     */
    public data class Mutation(
        public val requestId: RequestId,
        public val udfPath: String,
        public val args: List<ConvexValue>,
        public val componentPath: String? = null,
    ) : ClientMessage

    /**
     * Invokes an action.
     *
     * @property requestId correlates the response.
     * @property udfPath the function path.
     * @property args the arguments.
     * @property componentPath an optional component to call within.
     */
    public data class Action(
        public val requestId: RequestId,
        public val udfPath: String,
        public val args: List<ConvexValue>,
        public val componentPath: String? = null,
    ) : ClientMessage

    /**
     * Updates the session's authentication.
     *
     * @property baseVersion the identity version the change applies to.
     * @property token the new authentication state.
     */
    public data class Authenticate(
        public val baseVersion: IdentityVersion,
        public val token: AuthenticationToken,
    ) : ClientMessage

    /**
     * Sends a client analytics event.
     *
     * @property eventType the event name.
     * @property event the event payload.
     */
    public data class Event(
        public val eventType: String,
        public val event: ConvexValue,
    ) : ClientMessage
}
