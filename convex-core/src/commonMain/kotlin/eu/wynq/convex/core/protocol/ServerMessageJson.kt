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
package eu.wynq.convex.core.protocol

import eu.wynq.convex.core.internal.LittleEndianBase64
import eu.wynq.convex.core.value.ConvexJson
import eu.wynq.convex.core.value.ConvexJsonException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON codec for [ServerMessage].
 *
 * Field names are camelCase with a `type` discriminator. Timestamps are base64
 * little-endian text. The `errorData` field is emitted only for a `ConvexError`
 * and its **presence** (not its value) selects [ErrorPayload.ErrorData], which
 * is the concrete case of the "key presence over key value" guardrail.
 *
 * The upstream decoder reads `serverTs` as an integer while its encoder writes a
 * base64 string, so decoding accepts either; encoding always writes the string.
 */
public object ServerMessageJson {

    /**
     * Encodes [message] to compact JSON.
     *
     * @param message the message to encode.
     * @return the wire text.
     */
    public fun encode(message: ServerMessage): String = when (message) {
        is ServerMessage.Transition -> transitionElement(message)
        is ServerMessage.TransitionChunk -> transitionChunkElement(message)
        is ServerMessage.MutationResponse -> responseElement(
            type = "MutationResponse",
            requestId = message.requestId,
            result = message.result,
            ts = message.ts,
            logLines = message.logLines,
        )
        is ServerMessage.ActionResponse -> responseElement(
            type = "ActionResponse",
            requestId = message.requestId,
            result = message.result,
            ts = null,
            logLines = message.logLines,
        )
        is ServerMessage.AuthError -> authErrorElement(message)
        is ServerMessage.FatalError -> JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("FatalError"),
                "error" to JsonPrimitive(message.error),
            ),
        )
        ServerMessage.Ping -> JsonObject(linkedMapOf("type" to JsonPrimitive("Ping")))
    }.toString()

    /**
     * Decodes a server message.
     *
     * @param text the wire text.
     * @return the decoded message.
     * @throws ConvexJsonException when the text is not a valid server message.
     */
    public fun decode(text: String): ServerMessage {
        val message = parseJsonObject(text)
        return when (val type = message.string("type")) {
            "Transition" -> decodeTransition(message)
            "TransitionChunk" -> decodeTransitionChunk(message)
            "MutationResponse" -> {
                val result = decodeResult(message)
                ServerMessage.MutationResponse(
                    requestId = RequestId(message.uint("requestId")),
                    result = result,
                    ts = message.optionalTimestamp("ts"),
                    logLines = message.stringList("logLines"),
                )
            }
            "ActionResponse" -> ServerMessage.ActionResponse(
                requestId = RequestId(message.uint("requestId")),
                result = decodeResult(message),
                logLines = message.stringList("logLines"),
            )
            "AuthError" -> ServerMessage.AuthError(
                error = message.string("error"),
                baseVersion = message.optionalLong("baseVersion")?.toUInt()?.let(::IdentityVersion),
                authUpdateAttempted = message.optionalBoolean("authUpdateAttempted"),
            )
            "FatalError" -> ServerMessage.FatalError(message.string("error"))
            "Ping" -> ServerMessage.Ping
            else -> throw ConvexJsonException("unknown server message type '$type'")
        }
    }

    private fun transitionElement(message: ServerMessage.Transition): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("Transition"),
            "startVersion" to stateVersionElement(message.startVersion),
            "endVersion" to stateVersionElement(message.endVersion),
            "modifications" to JsonArray(message.modifications.map(::modificationElement)),
            "clientClockSkew" to (message.clientClockSkew?.let { JsonPrimitive(it) } ?: JsonNull),
            // The backend sends serverTs as a plain (large) integer, unlike the
            // base64 used for StateVersion.ts and MutationResponse.ts. Verified
            // against a captured Transition frame.
            "serverTs" to (message.serverTs?.let { JsonPrimitive(it.value.toLong()) } ?: JsonNull),
        ),
    )

    private fun transitionChunkElement(message: ServerMessage.TransitionChunk): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("TransitionChunk"),
            "chunk" to JsonPrimitive(message.chunk),
            "partNumber" to JsonPrimitive(message.partNumber.toLong()),
            "totalParts" to JsonPrimitive(message.totalParts.toLong()),
            "transitionId" to JsonPrimitive(message.transitionId),
        ),
    )

    private fun stateVersionElement(version: StateVersion): JsonObject = JsonObject(
        linkedMapOf(
            "querySet" to JsonPrimitive(version.querySet.value.toLong()),
            "identity" to JsonPrimitive(version.identity.value.toLong()),
            "ts" to JsonPrimitive(LittleEndianBase64.encodeULong(version.ts.value)),
        ),
    )

    private fun modificationElement(modification: StateModification): JsonObject = when (modification) {
        is StateModification.QueryUpdated -> JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("QueryUpdated"),
                "queryId" to JsonPrimitive(modification.queryId.value.toLong()),
                "value" to ConvexJson.toJsonElement(modification.value),
                "logLines" to JsonArray(modification.logLines.map(::JsonPrimitive)),
                "journal" to (modification.journal?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
        is StateModification.QueryFailed -> queryFailedElement(modification)
        is StateModification.QueryRemoved -> JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("QueryRemoved"),
                "queryId" to JsonPrimitive(modification.queryId.value.toLong()),
            ),
        )
    }

    private fun queryFailedElement(modification: StateModification.QueryFailed): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("QueryFailed"),
            "queryId" to JsonPrimitive(modification.queryId.value.toLong()),
            "errorMessage" to JsonPrimitive(modification.errorMessage),
            "logLines" to JsonArray(modification.logLines.map(::JsonPrimitive)),
            "journal" to (modification.journal?.let(::JsonPrimitive) ?: JsonNull),
        )
        modification.errorData?.let { fields["errorData"] = ConvexJson.toJsonElement(it) }
        return JsonObject(fields)
    }

    private fun responseElement(
        type: String,
        requestId: RequestId,
        result: ConvexResult,
        ts: Timestamp?,
        logLines: List<String>,
    ): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive(type),
            "requestId" to JsonPrimitive(requestId.value.toLong()),
            "success" to JsonPrimitive(result is ConvexResult.Success),
            "result" to when (result) {
                is ConvexResult.Success -> ConvexJson.toJsonElement(result.value)
                is ConvexResult.Failure -> JsonPrimitive(result.error.message)
            },
        )
        if (type == MUTATION_RESPONSE) {
            fields["ts"] = ts?.let { JsonPrimitive(LittleEndianBase64.encodeULong(it.value)) } ?: JsonNull
        }
        fields["logLines"] = JsonArray(logLines.map(::JsonPrimitive))
        (result as? ConvexResult.Failure)?.error?.let { error ->
            if (error is ErrorPayload.ErrorData) {
                fields["errorData"] = ConvexJson.toJsonElement(error.data)
            }
        }
        return JsonObject(fields)
    }

    private fun authErrorElement(message: ServerMessage.AuthError): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("AuthError"),
            "error" to JsonPrimitive(message.error),
            "baseVersion" to (message.baseVersion?.let { JsonPrimitive(it.value.toLong()) } ?: JsonNull),
        )
        message.authUpdateAttempted?.let { fields["authUpdateAttempted"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun decodeTransition(message: JsonObject): ServerMessage.Transition = ServerMessage.Transition(
        startVersion = decodeStateVersion(message.requireObject("startVersion")),
        endVersion = decodeStateVersion(message.requireObject("endVersion")),
        modifications = message.requireArray("modifications").map(::decodeModification),
        clientClockSkew = message.optionalLong("clientClockSkew"),
        serverTs = message.optionalTimestamp("serverTs"),
    )

    private fun decodeTransitionChunk(message: JsonObject): ServerMessage.TransitionChunk =
        ServerMessage.TransitionChunk(
            chunk = message.string("chunk"),
            partNumber = message.uint("partNumber"),
            totalParts = message.uint("totalParts"),
            transitionId = message.string("transitionId"),
        )

    private fun decodeStateVersion(message: JsonObject): StateVersion = StateVersion(
        querySet = QuerySetVersion(message.uint("querySet")),
        identity = IdentityVersion(message.uint("identity")),
        ts = message.timestamp("ts"),
    )

    private fun decodeModification(element: JsonElement): StateModification {
        val obj = element as? JsonObject ?: throw ConvexJsonException("modification must be an object")
        return when (val type = obj.string("type")) {
            "QueryUpdated" -> StateModification.QueryUpdated(
                queryId = QueryId(obj.uint("queryId")),
                value = obj.value("value"),
                logLines = obj.stringList("logLines"),
                journal = obj.optionalString("journal"),
            )
            "QueryFailed" -> StateModification.QueryFailed(
                queryId = QueryId(obj.uint("queryId")),
                errorMessage = obj.string("errorMessage"),
                logLines = obj.stringList("logLines"),
                journal = obj.optionalString("journal"),
                // Presence, not value: an absent key means no payload, while a
                // present `null` is ConvexValue.Null.
                errorData = if (obj.has("errorData")) obj.value("errorData") else null,
            )
            "QueryRemoved" -> StateModification.QueryRemoved(QueryId(obj.uint("queryId")))
            else -> throw ConvexJsonException("unknown state modification '$type'")
        }
    }

    private fun decodeResult(message: JsonObject): ConvexResult {
        if (message.boolean("success")) {
            return ConvexResult.Success(message.value("result"))
        }
        val resultElement = message["result"] ?: throw ConvexJsonException("response lacks result")
        val text = (resultElement as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ConvexJsonException("failed response result must be a string")
        val error = if (message.has("errorData")) {
            ErrorPayload.ErrorData(text, message.value("errorData"))
        } else {
            ErrorPayload.Message(text)
        }
        return ConvexResult.Failure(error)
    }

    private const val MUTATION_RESPONSE = "MutationResponse"
}
