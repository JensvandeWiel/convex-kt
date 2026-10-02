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
package eu.wynq.convex.auth

/**
 * Compile-time marker for the `convex-auth` module.
 *
 * `convex-auth` will implement the token lifecycle and JWT verification
 * (RS256 + ES256) behind an `expect`/`actual` crypto boundary, because the
 * signing primitives differ per platform (plan step 6). Until then this marker
 * keeps the module publishable.
 */
public object ConvexAuthModule
