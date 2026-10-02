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

import kotlinx.coroutines.channels.Channel

/**
 * An in-memory [SyncProtocol] for tests.
 *
 * Frames the client sends are recorded in [sent]; server frames are injected
 * with [push]. This is what lets the client be tested without a network.
 */
internal class FakeSyncProtocol : SyncProtocol {
    private val incoming = Channel<String>(Channel.UNLIMITED)

    /** Every frame the client has sent, in order. */
    val sent = mutableListOf<String>()

    override suspend fun send(text: String) {
        sent += text
    }

    override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun close() {
        incoming.close()
    }

    /** Injects a server frame. */
    fun push(text: String) {
        incoming.trySend(text)
    }
}
