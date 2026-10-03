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
package eu.wynq.convex.core

import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.ErrorPayload

/**
 * A failure reported by the backend for a query, mutation, or action.
 *
 * This is distinct from the transport failures ([ConvexException] subtypes such
 * as a closed connection or a timeout): it means the call reached the server and
 * the server rejected it. A typed call surfaces it by throwing, so the value
 * type of `client.mutate(...)` can be the decoded result itself rather than a
 * result-or-error wrapper — the same shape a throwing RPC has.
 *
 * @property payload why the call failed, exactly as the server sent it.
 */
public class ConvexError(
    public val payload: ErrorPayload,
) : ConvexException(payload.message)

/**
 * Converts a transport [ConvexResult] into the typed-call contract: a value on
 * success, a thrown [ConvexError] on failure.
 *
 * Centralised so every typed call path behaves identically and the
 * `ConvexResult` wrapper does not leak into user code.
 *
 * @param result the raw result from the transport.
 * @return the success value.
 * @throws ConvexError when [result] is a [ConvexResult.Failure].
 */
public fun ConvexResult.orThrow(): eu.wynq.convex.core.value.ConvexValue = when (this) {
    is ConvexResult.Success -> value
    is ConvexResult.Failure -> throw ConvexError(error)
}
