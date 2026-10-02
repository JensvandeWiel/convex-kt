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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * @property reconnectPolicy how an unexpected disconnect is retried.
 * @property callTimeoutMillis how long a mutation or action waits before
 *   failing, or `null` to wait indefinitely.
 */
public class ConvexSyncClient(
    private val factory: SyncProtocolFactory,
    private val scope: CoroutineScope,
    private val sessionId: SessionId = SessionId.random(),
    private val authFetcher: AuthTokenFetcher? = null,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val callTimeoutMillis: Long? = null,
) {
    private val localState = LocalSyncState()
    private val remoteState = RemoteQuerySet()
    private val outgoing = Channel<ClientMessage>(Channel.UNLIMITED)
    private val pending = mutableMapOf<RequestId, CompletableDeferred<ConvexResult>>()
    private val resultsState = MutableStateFlow<Map<QueryId, ConvexResult>>(emptyMap())
    private val connectionStateState = MutableStateFlow(ConnectionState.Disconnected)
    private val authErrorsState = MutableSharedFlow<String>(extraBufferCapacity = AUTH_ERROR_BUFFER)
    private val transitionChunks = mutableMapOf<String, ChunkBuffer>()

    private var nextRequestId = 0u
    private var connectionCount = 0u
    private var connection: SyncProtocol? = null
    private var senderJob: Job? = null
    private var receiverJob: Job? = null
    private var reconnectJob: Job? = null
    private var optimistic: OptimisticUpdate? = null

    /** The latest known result for every subscribed query. */
    public val results: StateFlow<Map<QueryId, ConvexResult>> = resultsState.asStateFlow()

    /** The connection lifecycle, for UI and diagnostics. */
    public val connectionState: StateFlow<ConnectionState> = connectionStateState.asStateFlow()

    /**
     * Authentication failures reported by the server.
     *
     * Emitted when a token is rejected or expires; the client does not clear its
     * subscriptions, so the app can refresh credentials and keep rendering.
     */
    public val authErrors: SharedFlow<String> = authErrorsState.asSharedFlow()

    /**
     * Opens a connection and starts the send and receive loops.
     *
     * @throws IllegalStateException when already connected.
     */
    public suspend fun connect() {
        check(connection == null) { "already connected" }
        establish(reconnecting = false)
    }

    /**
     * Replaces the connection and restores the session on the server.
     *
     * The server has forgotten the previous connection, so the identity version
     * resets, authentication is refetched (forcing a refresh), and every
     * subscription is re-added. Known results are kept until the server's
     * transitions replace them.
     */
    public suspend fun reconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        establish(reconnecting = true)
    }

    private suspend fun establish(reconnecting: Boolean) {
        connectionStateState.value = ConnectionState.Connecting
        if (reconnecting) {
            senderJob?.cancel()
            receiverJob?.cancel()
            connection?.close()
            connection = null
            // Drop anything queued for the dead connection; the resend below is
            // the authoritative state.
            while (outgoing.tryReceive().isSuccess) {
                // discard
            }
        }
        val open = factory.connect()
        connection = open
        outgoing.trySend(connectMessage())
        if (reconnecting) {
            localState.resetIdentityVersion()
        }
        sendAuthentication(forceRefresh = reconnecting)
        // Establish the query set after Connect: this sends subscriptions that
        // were requested before a connection existed, and it resends them on a
        // reconnect, in both cases from version zero with any known journals.
        if (localState.queryCount > 0) {
            outgoing.trySend(localState.resendQueries { remoteState.journal(it) })
        }
        connectionStateState.value = ConnectionState.Connected
        senderJob = scope.launch { sendLoop(open) }
        receiverJob = scope.launch {
            receiveLoop(open)
            onConnectionClosed()
        }
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

    /** Schedules a backoff reconnect after the receive loop ends. */
    private fun onConnectionClosed() {
        connection = null
        connectionStateState.value = ConnectionState.Disconnected
        senderJob?.cancel()
        if (!reconnectPolicy.automatic) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMillis = reconnectPolicy.initialDelayMillis
            while (isActive && connection == null) {
                delay(delayMillis)
                delayMillis = minOf(
                    (delayMillis * reconnectPolicy.multiplier).toLong(),
                    reconnectPolicy.maxDelayMillis,
                )
                try {
                    establish(reconnecting = true)
                    return@launch
                } catch (expected: Exception) {
                    // Keep retrying with backoff until a connection opens.
                }
            }
        }
    }

    /**
     * Subscribes to a query, reusing the server-side query when an identical
     * subscription already exists.
     *
     * May be called before [connect]: the request is queued and sent once a
     * connection opens, and it survives reconnects.
     *
     * @param udfPath the function path, for example `messages:list`.
     * @param args the single argument object.
     * @return the subscriber handle.
     */
    public fun subscribe(udfPath: String, args: Map<String, ConvexValue> = emptyMap()): SubscriberId {
        val subscription = localState.subscribe(udfPath, args)
        // Before connecting, the query set is established on connect instead, so
        // the Change cannot be sent out of order ahead of Connect.
        if (connection != null) {
            subscription.message?.let(outgoing::trySend)
        }
        return subscription.subscriberId
    }

    /**
     * Removes a subscriber, unsubscribing on the server when it was the last.
     *
     * @param subscriberId a handle from [subscribe].
     */
    public fun unsubscribe(subscriberId: SubscriberId) {
        val message = localState.unsubscribe(subscriberId)
        if (connection != null) {
            message?.let(outgoing::trySend)
        }
    }

    /**
     * Runs a mutation and waits for its response.
     *
     * @param udfPath the function path.
     * @param args the single argument object.
     * @param optimistic an optional prediction shown until the next transition.
     * @return the mutation's result.
     * @throws ConvexClientException when [callTimeoutMillis] elapses first.
     */
    public suspend fun mutate(
        udfPath: String,
        args: Map<String, ConvexValue>,
        optimistic: OptimisticUpdate? = null,
    ): ConvexResult = call(optimistic) { requestId ->
        ClientMessage.Mutation(requestId, udfPath, listOf(ConvexValue.Object(args)))
    }

    /**
     * Runs an action and waits for its response.
     *
     * @param udfPath the function path.
     * @param args the single argument object.
     * @param optimistic an optional prediction shown until the next transition.
     * @return the action's result.
     * @throws ConvexClientException when [callTimeoutMillis] elapses first.
     */
    public suspend fun action(
        udfPath: String,
        args: Map<String, ConvexValue>,
        optimistic: OptimisticUpdate? = null,
    ): ConvexResult = call(optimistic) { requestId ->
        ClientMessage.Action(requestId, udfPath, listOf(ConvexValue.Object(args)))
    }

    /** Cancels the loops and closes the connection. */
    public suspend fun close() {
        reconnectJob?.cancel()
        senderJob?.cancel()
        receiverJob?.cancel()
        connection?.close()
        connection = null
        connectionStateState.value = ConnectionState.Disconnected
        reconnectJob = null
        senderJob = null
        receiverJob = null
    }

    private suspend fun call(
        optimistic: OptimisticUpdate?,
        build: (RequestId) -> ClientMessage,
    ): ConvexResult {
        if (optimistic != null) {
            this.optimistic = optimistic
            publish()
        }
        val requestId = RequestId(nextRequestId)
        nextRequestId += 1u
        val deferred = CompletableDeferred<ConvexResult>()
        pending[requestId] = deferred
        outgoing.send(build(requestId))
        return try {
            awaitResult(deferred)
        } finally {
            pending.remove(requestId)
            // If no transition arrived during the call, the response itself is
            // the acknowledgement, so drop the prediction now. When a transition
            // did arrive it already cleared the prediction, so this is a no-op.
            if (optimistic != null) {
                this.optimistic = null
                publish()
            }
        }
    }

    /** Republishes results, applying any optimistic prediction. */
    private fun publish() {
        val prediction = optimistic
        resultsState.value = prediction?.apply(remoteState.results()) ?: remoteState.results()
    }

    private suspend fun awaitResult(deferred: CompletableDeferred<ConvexResult>): ConvexResult {
        val timeout = callTimeoutMillis ?: return deferred.await()
        return withTimeoutOrNull(timeout) { deferred.await() }
            ?: throw ConvexClientException("call did not complete within ${timeout}ms")
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
            is ServerMessage.AuthError -> authErrorsState.tryEmit(message.error)
            is ServerMessage.FatalError -> return false
            is ServerMessage.TransitionChunk -> handleChunk(message)
            ServerMessage.Ping -> Unit
        }
        return true
    }

    /**
     * Buffers transition chunks and applies the transition once every part has
     * arrived. A chunked transition is otherwise lost, which is why this is not
     * a no-op.
     */
    private fun handleChunk(chunk: ServerMessage.TransitionChunk) {
        val totalParts = chunk.totalParts.toInt()
        if (totalParts <= 0) return
        val buffer = transitionChunks.getOrPut(chunk.transitionId) { ChunkBuffer(totalParts) }
        buffer.append(chunk.partNumber.toInt(), chunk.chunk)
        if (!buffer.isComplete()) return
        transitionChunks.remove(chunk.transitionId)
        val reassembled = ServerMessageJson.decode(buffer.join())
        if (reassembled is ServerMessage.Transition) {
            applyTransition(reassembled)
        }
    }

    private fun applyTransition(transition: ServerMessage.Transition) {
        when (remoteState.transition(transition)) {
            is TransitionOutcome.Applied -> {
                // The server has caught up; drop any prediction it supersedes.
                optimistic = null
                publish()
            }
            is TransitionOutcome.VersionMismatch -> Unit
        }
    }

    /** Accumulates the parts of one chunked transition. */
    private class ChunkBuffer(private val totalParts: Int) {
        private val parts = arrayOfNulls<String>(totalParts)
        private var received = 0

        fun append(index: Int, chunk: String) {
            if (index !in parts.indices || parts[index] != null) return
            parts[index] = chunk
            received += 1
        }

        fun isComplete(): Boolean = received == totalParts

        fun join(): String = parts.joinToString("") { it ?: "" }
    }

    private companion object {
        private const val AUTH_ERROR_BUFFER = 8
    }
}
