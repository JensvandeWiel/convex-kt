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
 * A snapshot of the session's server-side state, attached to every transition.
 *
 * @property querySet the subscribed-query version.
 * @property identity the authentication version.
 * @property ts the server timestamp this version corresponds to.
 */
public data class StateVersion(
    public val querySet: QuerySetVersion,
    public val identity: IdentityVersion,
    public val ts: Timestamp,
)

/**
 * A change applied by a [ServerMessage.Transition].
 *
 * Closed so a new modification kind fails compilation instead of being ignored.
 */
public sealed interface StateModification {
    /**
     * A query produced a new value.
     *
     * @property queryId the query that changed.
     * @property value the new value.
     * @property logLines `console.log` output from the function.
     * @property journal an opaque pagination token.
     */
    public data class QueryUpdated(
        public val queryId: QueryId,
        public val value: ConvexValue,
        public val logLines: List<String>,
        public val journal: String?,
    ) : StateModification

    /**
     * A query failed.
     *
     * @property queryId the query that failed.
     * @property errorMessage the redacted error message.
     * @property logLines `console.log` output from the function.
     * @property journal an opaque pagination token.
     * @property errorData the `ConvexError` payload, or `null` when the failure
     *   was an ordinary error. A present-but-`null` payload is
     *   [ConvexValue.Null] and is distinct from an absent one.
     */
    public data class QueryFailed(
        public val queryId: QueryId,
        public val errorMessage: String,
        public val logLines: List<String>,
        public val journal: String?,
        public val errorData: ConvexValue?,
    ) : StateModification

    /**
     * A query was removed from the set.
     *
     * @property queryId the query that was removed.
     */
    public data class QueryRemoved(public val queryId: QueryId) : StateModification
}

/**
 * Why a mutation or action failed.
 *
 * The distinction matters: [ErrorData] carries a `ConvexError` payload that the
 * server never redacts, while [Message] is an ordinary error a production
 * deployment may redact.
 */
public sealed interface ErrorPayload {
    /** The human-readable message, present on both variants. */
    public val message: String

    /**
     * An ordinary error.
     *
     * @property message the redacted message.
     */
    public data class Message(override val message: String) : ErrorPayload

    /**
     * A `ConvexError` with a structured payload.
     *
     * @property message the message.
     * @property data the application payload.
     */
    public data class ErrorData(
        override val message: String,
        public val data: ConvexValue,
    ) : ErrorPayload
}

/**
 * The outcome of a mutation or action.
 */
public sealed interface CallResult {
    /**
     * The call returned a value.
     *
     * @property value the returned value.
     */
    public data class Success(public val value: ConvexValue) : CallResult

    /**
     * The call threw.
     *
     * @property error why it failed.
     */
    public data class Failure(public val error: ErrorPayload) : CallResult
}

/**
 * A message the server sends over the sync WebSocket.
 *
 * Closed so the decoder must handle every variant. The wire names are camelCase
 * with a `type` discriminator, matching the upstream serializer.
 */
public sealed interface ServerMessage {
    /**
     * A batch of state changes.
     *
     * @property startVersion the state before the changes.
     * @property endVersion the state after the changes.
     * @property modifications the changes.
     * @property clientClockSkew the measured difference between the client and
     *   server clocks, if the client sent its clock.
     * @property serverTs the server's send time, if present.
     */
    public data class Transition(
        public val startVersion: StateVersion,
        public val endVersion: StateVersion,
        public val modifications: List<StateModification>,
        public val clientClockSkew: Long?,
        public val serverTs: Timestamp?,
    ) : ServerMessage

    /**
     * A transition split across multiple frames.
     *
     * @property chunk the serialized transition fragment.
     * @property partNumber the zero-based part index.
     * @property totalParts the number of parts.
     * @property transitionId groups the parts of one transition.
     */
    public data class TransitionChunk(
        public val chunk: String,
        public val partNumber: UInt,
        public val totalParts: UInt,
        public val transitionId: String,
    ) : ServerMessage

    /**
     * The result of a mutation.
     *
     * @property requestId the request this responds to.
     * @property result the success value or the failure.
     * @property ts the commit timestamp, if present.
     * @property logLines `console.log` output from the function.
     */
    public data class MutationResponse(
        public val requestId: RequestId,
        public val result: CallResult,
        public val ts: Timestamp?,
        public val logLines: List<String>,
    ) : ServerMessage

    /**
     * The result of an action.
     *
     * @property requestId the request this responds to.
     * @property result the success value or the failure.
     * @property logLines `console.log` output from the function.
     */
    public data class ActionResponse(
        public val requestId: RequestId,
        public val result: CallResult,
        public val logLines: List<String>,
    ) : ServerMessage

    /**
     * Authentication failed or expired.
     *
     * @property error the message.
     * @property baseVersion the identity version this applies to.
     * @property authUpdateAttempted whether the failure came from an update, as
     *   opposed to an expired session.
     */
    public data class AuthError(
        public val error: String,
        public val baseVersion: IdentityVersion?,
        public val authUpdateAttempted: Boolean?,
    ) : ServerMessage

    /**
     * The connection is unusable.
     *
     * @property error the message.
     */
    public data class FatalError(public val error: String) : ServerMessage

    /** A keepalive. */
    public data object Ping : ServerMessage
}
