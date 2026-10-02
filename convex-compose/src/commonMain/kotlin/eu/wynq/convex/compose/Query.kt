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
package eu.wynq.convex.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.wynq.convex.client.ConvexSyncClient
import eu.wynq.convex.core.functions.ConvexFunction
import eu.wynq.convex.core.value.ConvexValue

/**
 * Subscribes to a query for the lifetime of the composition.
 *
 * The subscription is created once per `(client, udfPath, args)` and disposed
 * when the composition leaves, so navigation does not leak server
 * subscriptions.
 *
 * @param T the decoded value type.
 * @param client the sync client.
 * @param udfPath the function path, for example `messages:list`.
 * @param args the argument object.
 * @param decoder converts raw values to `T`.
 * @return the query's current state.
 */
@Composable
public fun <T> rememberQuery(
    client: ConvexSyncClient,
    udfPath: String,
    args: Map<String, ConvexValue> = emptyMap(),
    decoder: ConvexDecoder<T>,
): QueryState<T> {
    val controller = remember(client, udfPath, args) {
        QueryController(client, udfPath, args, decoder)
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    val state by controller.state.collectAsState(initial = QueryState.Loading)
    return state
}

/**
 * Subscribes to the query described by a [ConvexFunction] descriptor.
 *
 * @param T the decoded value type.
 * @param client the sync client.
 * @param function a query descriptor from generated code.
 * @param args the argument object.
 * @param decoder converts raw values to `T`.
 * @return the query's current state.
 */
@Composable
public fun <T> rememberQuery(
    client: ConvexSyncClient,
    function: ConvexFunction,
    args: Map<String, ConvexValue> = emptyMap(),
    decoder: ConvexDecoder<T>,
): QueryState<T> = rememberQuery(client, function.path, args, decoder)
