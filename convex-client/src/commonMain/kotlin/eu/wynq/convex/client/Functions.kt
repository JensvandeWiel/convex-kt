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

import eu.wynq.convex.core.functions.ConvexFunction
import eu.wynq.convex.core.functions.ConvexFunctionKind
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.sync.SubscriberId
import eu.wynq.convex.core.value.ConvexValue

/**
 * Typed entry points that take a [ConvexFunction] descriptor instead of a raw
 * path string.
 *
 * Generated descriptors (`convex-codegen`) plug in here, so a call site reads
 * `client.subscribe(ConvexApi.messagesList)` and a mismatch between the kind and
 * the operation fails immediately rather than at runtime on the server.
 */

/**
 * Subscribes to a query described by [function].
 *
 * @param function a query descriptor.
 * @param args the single argument object.
 * @return the subscriber handle, or `null` if not connected.
 * @throws ConvexClientException when [function] is not a query.
 */
public fun ConvexSyncClient.subscribe(
    function: ConvexFunction,
    args: Map<String, ConvexValue> = emptyMap(),
): SubscriberId? {
    function.requireKind(ConvexFunctionKind.QUERY)
    return subscribe(function.path, args)
}

/**
 * Runs the mutation described by [function].
 *
 * @param function a mutation descriptor.
 * @param args the single argument object.
 * @return the mutation's result.
 * @throws ConvexClientException when [function] is not a mutation.
 */
public suspend fun ConvexSyncClient.mutate(
    function: ConvexFunction,
    args: Map<String, ConvexValue> = emptyMap(),
): ConvexResult {
    function.requireKind(ConvexFunctionKind.MUTATION)
    return mutate(function.path, args)
}

/**
 * Runs the action described by [function].
 *
 * @param function an action descriptor.
 * @param args the single argument object.
 * @return the action's result.
 * @throws ConvexClientException when [function] is not an action.
 */
public suspend fun ConvexSyncClient.action(
    function: ConvexFunction,
    args: Map<String, ConvexValue> = emptyMap(),
): ConvexResult {
    function.requireKind(ConvexFunctionKind.ACTION)
    return action(function.path, args)
}

private fun ConvexFunction.requireKind(expected: ConvexFunctionKind) {
    if (kind != expected) {
        throw ConvexClientException("'$path' is a ${kind.wireName}, expected a ${expected.wireName}")
    }
}
