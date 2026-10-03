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
import eu.wynq.convex.core.protocol.ErrorPayload
import eu.wynq.convex.core.value.ConvexJson
import eu.wynq.convex.core.value.ConvexValue
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One-off function calls over Convex's HTTP API.
 *
 * Complements the sync client for the case where a subscription is overkill:
 * a script, a server-side integration, or a single mutation. The sync client
 * remains the way to get live updates.
 *
 * Endpoints and payloads follow the documented HTTP API: `POST /api/query`,
 * `/api/mutation`, and `/api/action` with `{"path", "args", "format": "json"}`.
 *
 * @property deploymentUrl the deployment origin.
 * @property client the HTTP client.
 */
public class ConvexHttpApi(
    private val deploymentUrl: String,
    private val client: HttpClient,
) {
    private val base: String = deploymentUrl.trimEnd('/')

    /**
     * Calls a query.
     *
     * @param path the function path, for example `messages:list`.
     * @param args the argument object.
     * @param authHeader an optional `Authorization` value, for example
     *   `Bearer <jwt>` or `Convex <admin-key>`.
     * @return the call result.
     */
    public suspend fun query(
        path: String,
        args: Map<String, ConvexValue> = emptyMap(),
        authHeader: String? = null,
    ): ConvexResult = call("query", path, args, authHeader)

    /**
     * Calls a mutation.
     *
     * @param path the function path.
     * @param args the argument object.
     * @param authHeader an optional `Authorization` value.
     * @return the call result.
     */
    public suspend fun mutation(
        path: String,
        args: Map<String, ConvexValue> = emptyMap(),
        authHeader: String? = null,
    ): ConvexResult = call("mutation", path, args, authHeader)

    /**
     * Calls an action.
     *
     * @param path the function path.
     * @param args the argument object.
     * @param authHeader an optional `Authorization` value.
     * @return the call result.
     */
    public suspend fun action(
        path: String,
        args: Map<String, ConvexValue> = emptyMap(),
        authHeader: String? = null,
    ): ConvexResult = call("action", path, args, authHeader)

    private suspend fun call(
        kind: String,
        path: String,
        args: Map<String, ConvexValue>,
        authHeader: String?,
    ): ConvexResult {
        val body = buildJsonObject {
            put("path", JsonPrimitive(path))
            put("args", ConvexJson.toJsonElement(ConvexValue.Object(args)))
            put("format", JsonPrimitive("json"))
        }
        val response = client.post("$base/api/$kind") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            authHeader?.let { header(HttpHeaders.Authorization, it) }
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            throw ConvexClientException("HTTP $kind failed: ${response.status}")
        }
        return decode(response.bodyAsText())
    }

    private fun decode(text: String): ConvexResult {
        val body = Json.parseToJsonElement(text).jsonObject
        val status = body["status"]?.jsonPrimitive?.content
        if (status == "success") {
            val value = body["value"] ?: return ConvexResult.Success(ConvexValue.Null)
            return ConvexResult.Success(ConvexJson.fromJsonElement(value))
        }
        val message = body["errorMessage"]?.jsonPrimitive?.content ?: "unknown error"
        // Presence, not value: errorData marks a ConvexError payload.
        val error = if (body.containsKey("errorData")) {
            ErrorPayload.ErrorData(message, ConvexJson.fromJsonElement(body.getValue("errorData")))
        } else {
            ErrorPayload.Message(message)
        }
        return ConvexResult.Failure(error)
    }
}
