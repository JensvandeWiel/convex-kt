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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the client reconnects after an unexpected disconnect.
 *
 * Durations are [Duration], not millis `Long`s, so call sites read as
 * `initialDelay = 500.milliseconds` instead of a bare number whose unit is
 * only in the property name.
 *
 * @property automatic whether a lost connection is retried automatically.
 * @property initialDelay the first backoff delay.
 * @property maxDelay the largest delay between attempts.
 * @property multiplier the factor each delay grows by.
 */
public data class ReconnectPolicy(
    public val automatic: Boolean = true,
    public val initialDelay: Duration = 500.milliseconds,
    public val maxDelay: Duration = 30.seconds,
    public val multiplier: Double = 2.0,
)
