package eu.wynq.convex.example.chat

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eu.wynq.convex.client.ConvexSyncClient
import eu.wynq.convex.client.KtorSyncProtocolFactory
import eu.wynq.convex.client.syncUrl
import eu.wynq.convex.compose.ConvexDecoder
import eu.wynq.convex.compose.QueryState
import eu.wynq.convex.compose.rememberQuery
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.launch

/**
 * Entry point. Point it at a backend with the `CONVEX_URL` environment
 * variable; the default matches the conformance Docker backend.
 */
public fun main(): Unit = application {
    Window(onCloseRequest = ::exitApplication, title = "convex-kt chat") {
        MaterialTheme { ChatApp() }
    }
}

@Composable
private fun ChatApp() {
    val scope = rememberCoroutineScope()
    val deploymentUrl = System.getenv("CONVEX_URL") ?: "http://127.0.0.1:3210"
    val client = remember {
        ConvexSyncClient(KtorSyncProtocolFactory(syncUrl(deploymentUrl)), scope)
    }
    LaunchedEffect(client) { client.connect() }
    DisposableEffect(client) {
        onDispose { scope.launch { client.close() } }
    }

    val messages = rememberQuery(client, "messages:list", decoder = MessageListDecoder)
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        when (val state = messages) {
            QueryState.Loading -> CircularProgressIndicator()
            is QueryState.Failure -> Text("Error: ${state.error.message}")
            is QueryState.Success -> LazyColumn(Modifier.weight(1f)) {
                items(state.value) { message -> Text(message) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val body = draft
                    draft = ""
                    scope.launch {
                        client.mutate("messages:send", mapOf("body" to ConvexValue.String(body)))
                    }
                },
            ) {
                Text("Send")
            }
        }
    }
}

/** Turns the `messages:list` array of documents into their `body` strings. */
private val MessageListDecoder: ConvexDecoder<List<String>> = ConvexDecoder { value ->
    when (value) {
        is ConvexValue.Array -> value.value.mapNotNull { element ->
            val body = (element as? ConvexValue.Object)?.value?.get("body")
            (body as? ConvexValue.String)?.value
        }
        else -> emptyList()
    }
}
