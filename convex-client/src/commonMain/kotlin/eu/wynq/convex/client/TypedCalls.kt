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

import eu.wynq.convex.core.functions.ConvexAction
import eu.wynq.convex.core.functions.ConvexMutation
import eu.wynq.convex.core.functions.ConvexQuery
import eu.wynq.convex.core.functions.encodeArguments
import eu.wynq.convex.core.orThrow
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.sync.SubscriberId
import eu.wynq.convex.core.value.ConvexJsonException
import eu.wynq.convex.core.value.ConvexValue
import eu.wynq.convex.core.value.ConvexValueDecoder

/**
 * Typed call entry points driven by the descriptors `convex-codegen` emits.
 *
 * These are the call sites the generated `Api` object is built for:
 *
 * ```kotlin
 * client.subscribe(Api.Messages.list)                                  // no args
 * val length = client.mutate(Api.Messages.send, SendMessageRequest("hi"))
 * val echo = client.action(Api.Messages.echo, EchoRequest("hi"))
 * ```
 *
 * The argument type is carried by the descriptor (`ConvexMutation<Args,
 * Result>`), so a wrong or missing argument is a **compile error**, not a
 * runtime surprise. A no-argument function has `Args = Unit`, and the overload
 * without an argument is the only one that accepts it.
 *
 * Arguments are encoded with the core encoder, which preserves the
 * `Int64`/`Float64` distinction a plain JSON round trip would lose.
 */

/**
 * Subscribes to a query that takes no arguments.
 *
 * @param query the query descriptor, whose argument type is `Unit`.
 * @return the subscriber handle.
 */
public fun ConvexSyncClient.subscribe(query: ConvexQuery<Unit, *>): SubscriberId =
    subscribe(query.path, emptyMap())

/**
 * Subscribes to a query with an argument.
 *
 * @param Args the argument type.
 * @param query the query descriptor.
 * @param args the argument, encoded via the descriptor's serializer.
 * @return the subscriber handle.
 * @throws ConvexJsonException when [args] cannot be encoded.
 */
public fun <Args> ConvexSyncClient.subscribe(
    query: ConvexQuery<Args, *>,
    args: Args,
): SubscriberId = subscribe(query.path, query.encodeArguments(args))

/**
 * Runs a mutation that takes no arguments and returns the decoded result.
 *
 * @param Result the decoded result type.
 * @param mutation the mutation descriptor, whose argument type is `Unit`.
 * @return the decoded result.
 * @throws eu.wynq.convex.core.ConvexError when the server rejects the call.
 * @throws ConvexJsonException when the result cannot be decoded.
 */
public suspend fun <Result> ConvexSyncClient.mutate(mutation: ConvexMutation<Unit, Result>): Result =
    mutate(mutation, Unit)

/**
 * Runs a mutation and returns the decoded result.
 *
 * A mutation is transactional and always returns a value on success, so the
 * result is non-nullable: a failure throws rather than returning `null`.
 *
 * @param Args the argument type.
 * @param Result the decoded result type.
 * @param mutation the mutation descriptor.
 * @param args the argument, encoded via the descriptor's serializer.
 * @return the decoded result.
 * @throws eu.wynq.convex.core.ConvexError when the server rejects the call.
 * @throws ConvexJsonException when the argument cannot be encoded or the result
 *   cannot be decoded.
 */
public suspend fun <Args, Result> ConvexSyncClient.mutate(
    mutation: ConvexMutation<Args, Result>,
    args: Args,
): Result = mutation.decodeResult(mutate(mutation.path, mutation.encodeArguments(args)))

/**
 * Runs an action that takes no arguments and returns the decoded result.
 *
 * @param Result the decoded result type.
 * @param action the action descriptor, whose argument type is `Unit`.
 * @return the decoded result, or `null` when the action returned nothing.
 * @throws eu.wynq.convex.core.ConvexError when the server rejects the call.
 */
public suspend fun <Result> ConvexSyncClient.action(action: ConvexAction<Unit, Result>): Result? =
    action(action, Unit)

/**
 * Runs an action and returns the decoded result.
 *
 * An action may return nothing, so the result is nullable. An action is **not
 * retried** on reconnect: it may have side effects and a retry could
 * double-execute them. That rule lives in the call path, not in the caller.
 *
 * @param Args the argument type.
 * @param Result the decoded result type.
 * @param action the action descriptor.
 * @param args the argument, encoded via the descriptor's serializer.
 * @return the decoded result, or `null` when the action returned nothing.
 * @throws eu.wynq.convex.core.ConvexError when the server rejects the call.
 * @throws ConvexJsonException when the argument cannot be encoded or the result
 *   cannot be decoded.
 */
public suspend fun <Args, Result> ConvexSyncClient.action(
    action: ConvexAction<Args, Result>,
    args: Args,
): Result? = action.decodeResult(action(action.path, action.encodeArguments(args)))

/**
 * Turns a transport result into a decoded value, throwing on failure.
 *
 * A `null` result serializer means the generator could not model `returns`, so
 * `Result` is `ConvexValue` and the raw value is returned as-is.
 */
private fun <Result> ConvexMutation<*, Result>.decodeResult(result: ConvexResult): Result =
    decodeValue(resultSerializer, result.orThrow())

private fun <Result> ConvexAction<*, Result>.decodeResult(result: ConvexResult): Result? {
    val value = result.orThrow()
    if (value == ConvexValue.Null) return null
    return decodeValue(resultSerializer, value)
}

private fun <Result> decodeValue(
    serializer: kotlinx.serialization.KSerializer<Result>?,
    value: ConvexValue,
): Result {
    if (serializer == null) {
        // A missing serializer means the generator could not model `returns`,
        // so `Result` must be `ConvexValue`. This cannot be checked: `Result`
        // is erased, and threading `reified` through the public typed-call API
        // would trade a descriptor-construction mistake (which codegen cannot
        // produce, since it pairs the two) for inline-forever public
        // signatures. The contract is documented, not enforced.
        @Suppress("UNCHECKED_CAST")
        return value as Result
    }
    return ConvexValueDecoder.decode(serializer, value)
}
