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

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText

/**
 * Normalizes a Convex deployment URL into its sync WebSocket URL.
 *
 * Accepts `http(s)://` and already-`ws(s)://` inputs, and always targets the
 * `/api/sync` endpoint. The backend listens on the same host as the deployment.
 *
 * @param deploymentUrl the deployment origin, for example
 *   `https://example.convex.cloud` or `http://127.0.0.1:3210`.
 * @return the WebSocket URL to connect to.
 */
public fun syncUrl(deploymentUrl: String): String {
    val trimmed = deploymentUrl.trim().trimEnd('/')
    val websocket = when {
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
        else -> "ws://$trimmed"
    }
    return "$websocket/api/sync"
}

/**
 * A [HttpClient] with the WebSockets plugin installed and no engine pinned, so
 * the platform engine (OkHttp on JVM/Android, Darwin on Apple) is used.
 *
 * @return the client to pass to [KtorSyncProtocolFactory].
 */
public fun defaultHttpClient(): HttpClient = HttpClient {
    install(WebSockets)
}

/**
 * A [SyncProtocolFactory] that connects over Ktor WebSockets.
 *
 * @property socketUrl the full sync WebSocket URL; prefer building it with
 *   [syncUrl].
 * @property client the HTTP client, which must have WebSockets installed.
 */
public class KtorSyncProtocolFactory(
    private val socketUrl: String,
    private val client: HttpClient = defaultHttpClient(),
) : SyncProtocolFactory {
    override suspend fun connect(): SyncProtocol {
        val session = client.webSocketSession { url(socketUrl) }
        return KtorSyncProtocol(session)
    }
}

/** A [SyncProtocol] backed by one Ktor WebSocket session. */
private class KtorSyncProtocol(
    private val session: DefaultClientWebSocketSession,
) : SyncProtocol {
    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun receive(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            when (frame) {
                is Frame.Text -> return frame.readText()
                is Frame.Close -> return null
                else -> Unit
            }
        }
    }

    override suspend fun close() {
        session.close()
    }
}
