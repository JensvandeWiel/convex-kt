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
package eu.wynq.convex.core.value

import eu.wynq.convex.core.ConvexException

/**
 * Thrown when a table name, field name, or other Convex identifier is invalid.
 *
 * Validation is strict on purpose: the backend rejects these names anyway, and
 * failing at the call site names the offending character instead of surfacing
 * a round trip later as a generic deployment error.
 *
 * @property message why the name was rejected, naming the offending character.
 */
public class ConvexIdentifierException(
    message: String,
    cause: Throwable? = null,
) : ConvexException(message, cause)
