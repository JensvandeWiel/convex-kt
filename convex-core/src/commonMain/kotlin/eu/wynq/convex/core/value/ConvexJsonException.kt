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

/**
 * Thrown when a JSON document cannot be decoded into a [ConvexValue], or a
 * value cannot be encoded.
 *
 * Decoding is strict on purpose: the Convex wire format has ambiguous corners
 * (a tagged `$float` that would have fit in a plain number, retired `$set` and
 * `$map` types), and accepting them would hide a protocol mismatch instead of
 * surfacing it at the boundary.
 *
 * @property message why decoding failed.
 */
public class ConvexJsonException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
