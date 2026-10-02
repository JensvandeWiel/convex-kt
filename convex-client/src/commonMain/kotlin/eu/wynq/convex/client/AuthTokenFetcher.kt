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

import eu.wynq.convex.core.protocol.AuthenticationToken

/**
 * Supplies an authentication token to the client.
 *
 * The client calls this on every connection. A reconnect passes
 * `forceRefresh = true` because the previous token may have expired while the
 * socket was down.
 */
public fun interface AuthTokenFetcher {
    /**
     * Fetches the current authentication state.
     *
     * @param forceRefresh whether the caller wants a newly minted token rather
     *   than a cached one.
     * @return the authentication state; [AuthenticationToken.None] means signed
     *   out, and the client sends nothing in that case.
     */
    public suspend fun fetch(forceRefresh: Boolean): AuthenticationToken
}
