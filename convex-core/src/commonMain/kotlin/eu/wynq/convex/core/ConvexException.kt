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

/**
 * The root of every failure `convex-kt` throws.
 *
 * A single base lets an application catch all client failures at one point
 * (`catch (e: ConvexException)`) while still distinguishing causes by subtype,
 * and it keeps the library's exceptions out of the standard types an app may
 * already catch for unrelated reasons.
 *
 * @property message what went wrong.
 * @property cause the underlying failure, when there is one.
 */
public open class ConvexException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
