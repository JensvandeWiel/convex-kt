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

/**
 * How the client reconnects after an unexpected disconnect.
 *
 * @property automatic whether a lost connection is retried automatically.
 * @property initialDelayMillis the first backoff delay.
 * @property maxDelayMillis the largest delay between attempts.
 * @property multiplier the factor each delay grows by.
 */
public data class ReconnectPolicy(
    public val automatic: Boolean = true,
    public val initialDelayMillis: Long = 500,
    public val maxDelayMillis: Long = 30_000,
    public val multiplier: Double = 2.0,
)
