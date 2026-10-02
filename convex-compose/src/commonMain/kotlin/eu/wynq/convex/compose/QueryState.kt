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

import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.ErrorPayload
import eu.wynq.convex.core.value.ConvexValue

/**
 * The observable state of a query, as a UI consumes it.
 *
 * `Loading` means no server result has arrived yet, `Success` carries the
 * decoded value, and `Failure` carries the same [ErrorPayload] the core uses,
 * so a `ConvexError` payload is never flattened to a string here.
 *
 * @param T the decoded value type.
 */
public sealed interface QueryState<out T> {
    /** No result has arrived yet. */
    public data object Loading : QueryState<Nothing>

    /**
     * The query resolved.
     *
     * @property value the decoded value.
     */
    public data class Success<out T>(public val value: T) : QueryState<T>

    /**
     * The query failed.
     *
     * @property error why it failed.
     */
    public data class Failure(public val error: ErrorPayload) : QueryState<Nothing>
}

/**
 * Converts a raw [ConvexValue] into a typed value.
 *
 * The client returns untyped values; generated code or a hand-written decoder
 * turns them into the shapes an app uses.
 *
 * @param T the target type.
 */
public fun interface ConvexDecoder<T> {
    /**
     * Decodes a value.
     *
     * @param value the raw value.
     * @return the typed value.
     */
    public fun decode(value: ConvexValue): T

    /** Convenience decoders. */
    public companion object {
        /** A decoder that returns the raw [ConvexValue] unchanged. */
        public fun identity(): ConvexDecoder<ConvexValue> = ConvexDecoder { it }
    }
}

/**
 * Maps a raw query result into a [QueryState].
 *
 * @param T the decoded value type.
 * @param result the latest result, or `null` when none has arrived.
 * @param decoder the decoder for successful values.
 * @return the observable state.
 */
public fun <T> queryStateOf(result: ConvexResult?, decoder: ConvexDecoder<T>): QueryState<T> =
    when (result) {
        null -> QueryState.Loading
        is ConvexResult.Success -> QueryState.Success(decoder.decode(result.value))
        is ConvexResult.Failure -> QueryState.Failure(result.error)
    }
