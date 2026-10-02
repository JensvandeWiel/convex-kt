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
 * The client's connection lifecycle, for UI and diagnostics.
 *
 * A subscription can be requested before the client is [Connected]; the request
 * is queued and sent as soon as a connection opens, so callers do not have to
 * wait for connectivity before subscribing.
 */
public enum class ConnectionState {
    /** No connection is open. */
    Disconnected,

    /** A connection is being opened. */
    Connecting,

    /** A connection is open and messages can flow. */
    Connected,
}
