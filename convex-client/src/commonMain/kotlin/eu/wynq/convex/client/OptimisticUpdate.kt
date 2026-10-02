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

import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.protocol.QueryId

/**
 * Predicts query results for an in-flight mutation, so the UI can show the
 * expected change before the server confirms it.
 *
 * The prediction is applied to the current results and shown until the next
 * server transition arrives, at which point the server's truth replaces it.
 * Implementations should be pure and return a new map.
 */
public fun interface OptimisticUpdate {
    /**
     * Applies the prediction.
     *
     * @param results the current server results.
     * @return the predicted results.
     */
    public fun apply(results: Map<QueryId, ConvexResult>): Map<QueryId, ConvexResult>
}
