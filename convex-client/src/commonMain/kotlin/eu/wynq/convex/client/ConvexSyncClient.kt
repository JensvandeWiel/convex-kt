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

import eu.wynq.convex.core.protocol.AuthenticationToken
import eu.wynq.convex.core.protocol.ClientMessage
import eu.wynq.convex.core.protocol.ClientMessageJson
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.QueryId
import eu.wynq.convex.core.protocol.RequestId
import eu.wynq.convex.core.protocol.ServerMessage
import eu.wynq.convex.core.protocol.ServerMessageJson
import eu.wynq.convex.core.protocol.SessionId
import eu.wynq.convex.core.sync.LocalSyncState
import eu.wynq.convex.core.sync.RemoteQuerySet
import eu.wynq.convex.core.sync.SubscriberId
import eu.wynq.convex.core.sync.TransitionOutcome
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the sync protocol over a [SyncProtocol].
 *
 * It owns the outgoing message queue and the receive loop, and it is the only
 * place that combines [LocalSyncState] (what we want) with [RemoteQuerySet]
 * (what the server has told us). The state machine itself stays in
 * `convex-core`; this class is the coroutine plumbing around it.
 *
 * Concurrency: the client is intended to be confined to a single dispatcher
 * (the [scope]'s). Call its methods from that dispatcher, as a Compose
 * `LaunchedEffect` or a `MainScope` would.
 *
 * @property factory opens connections.
 * @property scope owns the send and receive coroutines.
 * @property sessionId the session identifier; random unless supplied.
 * @property authFetcher supplies the authentication state, if any.
 */
public class ConvexSyncClient(
    private val factory: SyncProtocolFactory,
    private val scope: CoroutineScope,
    private val sessionId: SessionId = SessionId.random(),
    private val authFetcher: AuthTokenFetcher? = null,
) {
    private val localState = LocalSyncState()
    private val remoteState = RemoteQuerySet()
    private val outgoing = Channel<ClientMessage>(Channel.UNLIMITED)
    private val pending = mutableMapOf<RequestId, CompletableDeferred<ConvexResult>>()
    private val resultsState = MutableStateFlow<Map<QueryId, ConvexResult>>(emptyMap())

    private var nextRequestId = 0u
    private var connectionCount = 0u
    private var connection: SyncProtocol? = null
    private var senderJob: Job? = null
    private var receiverJob: Job? = null

    /** The latest known result for every subscribed query. */
    public val results: StateFlow<Map<QueryId, ConvexResult>> = resultsState.asStateFlow()

    /**
     * Opens a connection and starts the send and receive loops.
     *
     * @throws IllegalStateException when already connected.
     */
    public suspend fun connect() {
        check(connection == null) { "already connected" }
        openConnection()
    }

    /**
     * Replaces the connection and restores the session on the server.
     *
     * The server has forgotten the previous connection, so the identity version
     * resets, authentication is refetched (forcing a refresh), and every
     * subscription is re-added. Known results are kept until the server's
     * transitions replace them.
     *
     * @throws IllegalStateException when not connected.
     */
    public suspend fun reconnect() {
        val previous = connection ?: error("not connected")
        senderJob?.cancel()
        receiverJob?.cancel()
        previous.close()
        connection = null

        val open = factory.connect()
        connection = open
        // Drop anything queued for the dead connection; the resend below is the
        // authoritative state.
        while (outgoing.tryReceive().isSuccess) {
            // discard
        }
        outgoing.trySend(connectMessage())
        localState.resetIdentityVersion()
        sendAuthentication(forceRefresh = true)
        outgoing.trySend(localState.resendQueries())
        senderJob = scope.launch { sendLoop(open) }
        receiverJob = scope.launch { receiveLoop(open) }
    }

    private suspend fun openConnection() {
        val open = factory.connect()
        connection = open
        outgoing.trySend(connectMessage())
        sendAuthentication(forceRefresh = false)
        senderJob = scope.launch { sendLoop(open) }
        receiverJob = scope.launch { receiveLoop(open) }
    }

    private fun connectMessage(): ClientMessage.Connect {
        val message = ClientMessage.Connect(
            sessionId = sessionId,
            connectionCount = connectionCount,
            lastCloseReason = if (connectionCount == 0u) "InitialConnect" else "Reconnect",
        )
        connectionCount += 1u
        return message
    }

    private suspend fun sendAuthentication(forceRefresh: Boolean) {
        val fetcher = authFetcher ?: return
        val token = fetcher.fetch(forceRefresh)
        if (token != AuthenticationToken.None) {
            outgoing.trySend(localState.authenticate(token))
        }
    }

    /**
     * Subscribes to a query, reusing the server-side query when an identical
     * subscription already exists.
     *
     * @param udfPath the function path, for example `messages:list`.
     * @param args the single argument object.
     * @return the subscriber handle, or `null` if not connected.
     */
    public fun subscribe(udfPath: String, args: Map<String, ConvexValue> = emptyMap()): SubscriberId? {
        if (connection == null) return null
        val subscription = localState.subscribe(udfPath, args)
        subscription.message?.let(outgoing::trySend)
        return subscription.subscriberId
    }

    /**
     * Removes a subscriber, unsubscribing on the server when it was the last.
     *
     * @param subscriberId a handle from [subscribe].
     */
    public fun unsubscribe(subscriberId: SubscriberId) {
        localState.unsubscribe(subscriberId)?.let(outgoing::trySend)
    }

    /**
     * Runs a mutation and waits for its response.
     *
     * @param udfPath the function path.
     * @param args the single argument object.
     * @return the mutation's result.
     */
    public suspend fun mutate(udfPath: String, args: Map<String, ConvexValue>): ConvexResult =
        call { requestId ->
            ClientMessage.Mutation(requestId, udfPath, listOf(ConvexValue.Object(args)))
        }

    /**
     * Runs an action and waits for its response.
     *
     * @param udfPath the function path.
     * @param args the single argument object.
     * @return the action's result.
     */
    public suspend fun action(udfPath: String, args: Map<String, ConvexValue>): ConvexResult =
        call { requestId ->
            ClientMessage.Action(requestId, udfPath, listOf(ConvexValue.Object(args)))
        }

    /** Cancels the loops and closes the connection. */
    public suspend fun close() {
        senderJob?.cancel()
        receiverJob?.cancel()
        connection?.close()
        connection = null
        senderJob = null
        receiverJob = null
    }

    private suspend fun call(build: (RequestId) -> ClientMessage): ConvexResult {
        val requestId = RequestId(nextRequestId)
        nextRequestId += 1u
        val deferred = CompletableDeferred<ConvexResult>()
        pending[requestId] = deferred
        outgoing.send(build(requestId))
        return deferred.await()
    }

    private suspend fun sendLoop(open: SyncProtocol) {
        for (message in outgoing) {
            open.send(ClientMessageJson.encode(message))
        }
    }

    private suspend fun receiveLoop(open: SyncProtocol) {
        var running = true
        while (running) {
            val text = open.receive()
            running = text != null && handle(text)
        }
    }

    /** Applies one received frame; returns `false` when the session is over. */
    private fun handle(text: String): Boolean {
        when (val message = ServerMessageJson.decode(text)) {
            is ServerMessage.Transition -> applyTransition(message)
            is ServerMessage.MutationResponse -> pending.remove(message.requestId)?.complete(message.result)
            is ServerMessage.ActionResponse -> pending.remove(message.requestId)?.complete(message.result)
            is ServerMessage.AuthError -> Unit
            is ServerMessage.FatalError -> return false
            is ServerMessage.TransitionChunk -> Unit
            ServerMessage.Ping -> Unit
        }
        return true
    }

    private fun applyTransition(transition: ServerMessage.Transition) {
        when (remoteState.transition(transition)) {
            is TransitionOutcome.Applied -> resultsState.value = remoteState.results()
            is TransitionOutcome.VersionMismatch -> Unit
        }
    }
}
