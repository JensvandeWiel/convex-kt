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
 * Compile-time marker for the `convex-core` module.
 *
 * `convex-core` will own the pure Kotlin value types, the JSON codecs that keep
 * 64-bit integer precision intact, the protocol message types, and the sync
 * state machine (plan step 4). This marker exists so the scaffold builds and
 * publishes an artifact until that work lands, and so `explicitApi()` has a
 * declaration to verify.
 */
public object ConvexCoreModule
