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
package eu.wynq.convex.core.functions

import eu.wynq.convex.core.value.ConvexJsonException
import eu.wynq.convex.core.value.ConvexValue
import eu.wynq.convex.core.value.ConvexValueEncoder
import kotlinx.serialization.KSerializer

/**
 * A typed query descriptor: a [ConvexFunction] of kind [ConvexFunctionKind.QUERY]
 * carrying its argument and result types.
 *
 * The descriptor is a **singleton** — it holds the immutable path, validators,
 * and serializers and is identical for every call. Per-call data lives in a
 * separate `Input` value, so a descriptor can be shared, compared, cached, and
 * generated once.
 *
 * The two type parameters are what make a call site type-safe:
 *
 * - [Args] is the query's argument type. A no-argument query uses `Unit`, and
 *   the client overload for such a query takes no argument at all, so passing an
 *   argument by mistake fails to compile.
 * - [Result] is the decoded result type, generated from the `returns` validator.
 *
 * @property function the underlying untyped descriptor.
 * @property argsSerializer the serializer for [Args]; `null` means [Args] is
 *   `Unit` and the argument is always the empty object.
 * @property resultSerializer the serializer for [Result]; `null` means the
 *   result is handed back as a raw [ConvexValue] because the `returns` validator
 *   was absent or was not modelled.
 */
public class ConvexQuery<Args, Result>(
    public val function: ConvexFunction,
    public val argsSerializer: KSerializer<Args>? = null,
    public val resultSerializer: KSerializer<Result>? = null,
) {
    init {
        function.requireKind(ConvexFunctionKind.QUERY)
    }

    /** The function path, for example `messages:list`. */
    public val path: String get() = function.path
}

/**
 * A typed mutation descriptor: a [ConvexFunction] of kind
 * [ConvexFunctionKind.MUTATION] carrying its argument and result types.
 *
 * A mutation is transactional and **always returns a value** on success, so
 * [Result] is non-nullable: a mutation either produces a result or fails. The
 * descriptor is a singleton; per-call data lives in a separate `Request` value.
 *
 * @property function the underlying untyped descriptor.
 * @property argsSerializer the serializer for [Args]; `null` means [Args] is
 *   `Unit` and the argument is always the empty object.
 * @property resultSerializer the serializer for [Result]; `null` means the
 *   result is handed back as a raw [ConvexValue].
 */
public class ConvexMutation<Args, Result>(
    public val function: ConvexFunction,
    public val argsSerializer: KSerializer<Args>? = null,
    public val resultSerializer: KSerializer<Result>? = null,
) {
    init {
        function.requireKind(ConvexFunctionKind.MUTATION)
    }

    /** The function path, for example `messages:send`. */
    public val path: String get() = function.path
}

/**
 * A typed action descriptor: a [ConvexFunction] of kind
 * [ConvexFunctionKind.ACTION] carrying its argument and result types.
 *
 * An action is **not transactional**, runs in the Convex runtime or Node.js,
 * and **may return nothing** — the operation document is explicit that an
 * action "can but does not have to return a value". Its result is therefore
 * optional ([Result] is nullable), unlike a mutation's.
 *
 * An action may also have side effects, so the client must **never retry** it;
 * re-sending after a reconnect could double-execute the effect. This is encoded
 * in the call path, not left to the caller to remember.
 *
 * @property function the underlying untyped descriptor.
 * @property argsSerializer the serializer for [Args]; `null` means [Args] is
 *   `Unit` and the argument is always the empty object.
 * @property resultSerializer the serializer for [Result]; `null` means the
 *   result is handed back as a raw [ConvexValue].
 */
public class ConvexAction<Args, Result>(
    public val function: ConvexFunction,
    public val argsSerializer: KSerializer<Args>? = null,
    public val resultSerializer: KSerializer<Result>? = null,
) {
    init {
        function.requireKind(ConvexFunctionKind.ACTION)
    }

    /** The function path, for example `messages:echo`. */
    public val path: String get() = function.path
}

/**
 * Verifies that a descriptor's underlying function has the expected kind.
 *
 * The kind is part of the descriptor's identity, so a mismatch is a programming
 * error (a descriptor of the wrong type reaching a call) rather than a server
 * rejection. It fails loudly at construction, so a hand-written or
 * mis-generated descriptor cannot reach the wire.
 */
private fun ConvexFunction.requireKind(expected: ConvexFunctionKind) {
    require(kind == expected) {
        "'$path' is a ${kind.wireName}, expected a ${expected.wireName}"
    }
}

/**
 * Encodes a query's argument using its own serializer.
 *
 * A `null` serializer means the argument type is `Unit`, which encodes to the
 * empty object the protocol expects for a no-argument function. Kept here, next
 * to the descriptor, so the client and the compose bindings cannot disagree on
 * the wire form.
 *
 * @param args the argument value.
 * @return the encoded argument object.
 * @throws eu.wynq.convex.core.value.ConvexJsonException when the argument cannot
 *   be encoded, or does not encode to an object.
 */
public fun <Args> ConvexQuery<Args, *>.encodeArguments(args: Args): Map<String, ConvexValue> =
    encodeDescriptorArguments(argsSerializer, args)

/**
 * Encodes a mutation's argument using its own serializer. See the query
 * overload for the contract.
 *
 * @param args the argument value.
 * @return the encoded argument object.
 */
public fun <Args> ConvexMutation<Args, *>.encodeArguments(args: Args): Map<String, ConvexValue> =
    encodeDescriptorArguments(argsSerializer, args)

/**
 * Encodes an action's argument using its own serializer. See the query
 * overload for the contract.
 *
 * @param args the argument value.
 * @return the encoded argument object.
 */
public fun <Args> ConvexAction<Args, *>.encodeArguments(args: Args): Map<String, ConvexValue> =
    encodeDescriptorArguments(argsSerializer, args)

private fun <Args> encodeDescriptorArguments(
    serializer: KSerializer<Args>?,
    args: Args,
): Map<String, ConvexValue> {
    if (serializer == null) return emptyMap()
    val encoded = ConvexValueEncoder.encode(serializer, args)
    return (encoded as? ConvexValue.Object)?.value
        ?: throw ConvexJsonException("arguments must encode to an object, got $encoded")
}
