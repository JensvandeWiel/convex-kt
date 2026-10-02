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

/**
 * The transport seam for the sync WebSocket.
 *
 * The client speaks text frames and never inspects the transport, so the same
 * logic runs over Ktor, an in-memory test double, or a replay of recorded
 * fixtures. Implementations must deliver frames in order and must make
 * [receive] return `null` exactly once, when the connection is finished.
 */
public interface SyncProtocol {
    /**
     * Sends one text frame.
     *
     * @param text the encoded message.
     */
    public suspend fun send(text: String)

    /**
     * Receives the next text frame.
     *
     * @return the frame, or `null` when the connection has closed.
     */
    public suspend fun receive(): String?

    /** Closes the connection and releases its resources. */
    public suspend fun close()
}

/**
 * Creates connected [SyncProtocol] instances.
 *
 * A factory rather than a single protocol because a client reconnects: each
 * connection is a fresh session from the transport's point of view.
 */
public fun interface SyncProtocolFactory {
    /**
     * Opens a connection.
     *
     * @return the connected protocol.
     */
    public suspend fun connect(): SyncProtocol
}
