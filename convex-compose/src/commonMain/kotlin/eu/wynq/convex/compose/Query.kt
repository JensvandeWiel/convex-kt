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
import eu.wynq.convex.core.functions.ConvexQuery
import eu.wynq.convex.core.functions.encodeArguments
import eu.wynq.convex.core.value.ConvexValue
import eu.wynq.convex.core.value.ConvexValueDecoder

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
    val controller = remember(client, udfPath, args, decoder) {
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

/**
 * Subscribes to a typed query from generated code, decoding its result with the
 * descriptor's own serializer.
 *
 * The decoded type comes from the descriptor ([Result]), so no decoder argument
 * is needed: `convex-codegen` already generated one from the `returns`
 * validator. When the descriptor has no result serializer — the `returns`
 * validator was absent or unmodelled — this is only usable with `Result =
 * ConvexValue`, and the overload below takes an explicit decoder instead.
 *
 * @param Result the decoded result type.
 * @param client the sync client.
 * @param query a typed query descriptor.
 * @return the query's current state.
 */
@Composable
public fun <Result> rememberQuery(
    client: ConvexSyncClient,
    query: ConvexQuery<Unit, Result>,
): QueryState<Result> = rememberQuery(client, query, Unit)

/**
 * Subscribes to a typed query with an argument.
 *
 * @param Args the argument type.
 * @param Result the decoded result type.
 * @param client the sync client.
 * @param query a typed query descriptor.
 * @param args the argument, encoded via the descriptor's serializer.
 * @return the query's current state.
 */
@Composable
public fun <Args, Result> rememberQuery(
    client: ConvexSyncClient,
    query: ConvexQuery<Args, Result>,
    args: Args,
): QueryState<Result> {
    val encoded = query.encodeArguments(args)
    return rememberQuery(client, query.path, encoded, query.decoder())
}

/**
 * Builds a decoder from the descriptor's result serializer.
 *
 * A `null` serializer means the generator could not model `returns`, so the raw
 * [ConvexValue] is returned unchanged; the caller is expected to use `Result =
 * ConvexValue` in that case.
 */
private fun <Result> ConvexQuery<*, Result>.decoder(): ConvexDecoder<Result> =
    resultSerializer
        ?.let { serializer -> ConvexDecoder { value -> ConvexValueDecoder.decode(serializer, value) } }
        ?: ConvexDecoder { value ->
            @Suppress("UNCHECKED_CAST")
            (value as Result)
        }
