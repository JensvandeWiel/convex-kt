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
package eu.wynq.convex.example.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eu.wynq.convex.client.ConnectionState
import eu.wynq.convex.client.ConvexSyncClient
import eu.wynq.convex.client.KtorSyncProtocolFactory
import eu.wynq.convex.client.syncUrl
import eu.wynq.convex.compose.ConvexDecoder
import eu.wynq.convex.compose.QueryController
import eu.wynq.convex.compose.QueryState
import eu.wynq.convex.core.ConvexException
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Entry point. Point it at a backend with the `CONVEX_URL` environment variable;
 * the default matches the conformance Docker backend.
 *
 * This is a worked example of the public API: `ConvexSyncClient` owns the
 * connection, `QueryController` tracks a subscription, and a send uses an
 * optimistic update so the message appears before the server confirms it.
 */
public fun main(): Unit = application {
    Window(onCloseRequest = ::exitApplication, title = "convex-kt chat") {
        MaterialTheme { ChatApp() }
    }
}

// Compose screens use PascalCase by convention; both lint engines flag that
// with their generic function rules, so the single composable is exempted
// instead of renamed into a style no Compose reader expects.
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun ChatApp() {
    val scope = rememberCoroutineScope()
    val deploymentUrl = System.getenv("CONVEX_URL") ?: "http://127.0.0.1:3210"
    val client = remember { ConvexSyncClient(KtorSyncProtocolFactory(syncUrl(deploymentUrl)), scope) }
    val connection by client.connectionState.collectAsState()
    var connectionError by remember { mutableStateOf<String?>(null) }

    // `subscribe` may be called before the connection exists; the client
    // establishes the query set once it connects.
    val controller = remember(client) {
        QueryController(client, "messages:list", decoder = MessageListDecoder)
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val messages by controller.state.collectAsState(initial = QueryState.Loading)

    LaunchedEffect(client) {
        try {
            client.connect()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ConvexException) {
            connectionError = failure.message ?: "could not connect"
        } catch (failure: IllegalStateException) {
            connectionError = failure.message ?: "could not connect"
        } catch (failure: IOException) {
            connectionError = failure.message ?: "could not connect"
        }
    }
    DisposableEffect(client) { onDispose { scope.launch { client.close() } } }

    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("convex-kt chat — $deploymentUrl", style = MaterialTheme.typography.titleMedium)
        Text("Connection: ${connection.name}${connectionError?.let { " ($it)" }.orEmpty()}")

        when (val state = messages) {
            QueryState.Loading -> CircularProgressIndicator()
            is QueryState.Failure -> Text("Error: ${state.error.message}", color = MaterialTheme.colorScheme.error)
            is QueryState.Success -> LazyColumn(modifier = Modifier.weight(1f)) {
                if (state.value.isEmpty()) {
                    item { Text("No messages yet.") }
                }
                items(state.value) { message -> Text("• $message") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Message") },
            )
            Button(
                enabled = draft.isNotBlank() && connection == ConnectionState.Connected,
                onClick = {
                    val body = draft.trim()
                    draft = ""
                    scope.launch {
                        client.mutate("messages:send", mapOf("body" to ConvexValue.String(body))) { results ->
                            predictAppend(results, controller.queryId, body)
                        }
                    }
                },
            ) {
                Text("Send")
            }
        }
    }
}

/** Predicts the new message appearing in `messages:list`. */
private fun predictAppend(
    results: Map<eu.wynq.convex.core.protocol.QueryId, ConvexResult>,
    queryId: eu.wynq.convex.core.protocol.QueryId,
    body: String,
): Map<eu.wynq.convex.core.protocol.QueryId, ConvexResult> {
    val appended = ConvexValue.Object(mapOf("body" to ConvexValue.String(body)))
    val current = (results[queryId] as? ConvexResult.Success)?.value
    // Exhaustive on purpose: anything that is not already a list restarts the
    // prediction from a single message instead of crashing the example.
    val predicted = when (current) {
        is ConvexValue.Array -> current.value + appended
        ConvexValue.Null,
        is ConvexValue.Int64,
        is ConvexValue.Float64,
        is ConvexValue.Boolean,
        is ConvexValue.String,
        is ConvexValue.Bytes,
        is ConvexValue.Object,
        null,
        -> listOf(appended)
    }
    return results + (queryId to ConvexResult.Success(ConvexValue.Array(predicted)))
}

/** Turns the `messages:list` array of documents into their `body` strings. */
private val MessageListDecoder: ConvexDecoder<List<String>> = ConvexDecoder { value ->
    when (value) {
        is ConvexValue.Array -> value.value.mapNotNull { element ->
            val body = (element as? ConvexValue.Object)?.value?.get("body")
            (body as? ConvexValue.String)?.value
        }
        ConvexValue.Null,
        is ConvexValue.Int64,
        is ConvexValue.Float64,
        is ConvexValue.Boolean,
        is ConvexValue.String,
        is ConvexValue.Bytes,
        is ConvexValue.Object,
        -> emptyList()
    }
}
