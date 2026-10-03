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
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON codec for [ClientMessage].
 *
 * Field names are camelCase and the discriminator is `type`, matching the
 * upstream serializer. Two upstream quirks are reproduced deliberately:
 * `args` and `modifications` are siblings of `type` rather than nested inside
 * the variant, and `AuthenticationToken` is flattened into the message with a
 * `tokenType` discriminator.
 *
 * Key order follows insertion order (the upstream enables `preserve_order`).
 * For `Connect` the order below is verified against the recorded conformance
 * fixture; the other variants preserve the same `type`-first convention, which
 * the backend accepts regardless of order.
 */
public object ClientMessageJson {

    /**
     * Encodes [message] to compact JSON.
     *
     * @param message the message to encode.
     * @return the wire text.
     */
    public fun encode(message: ClientMessage): String = when (message) {
        is ClientMessage.Connect -> connectElement(message)
        is ClientMessage.ModifyQuerySet -> modifyQuerySetElement(message)
        is ClientMessage.Mutation -> invocationElement(
            "Mutation",
            message.requestId,
            message.udfPath,
            message.args,
            message.componentPath,
        )
        is ClientMessage.Action -> invocationElement(
            "Action",
            message.requestId,
            message.udfPath,
            message.args,
            message.componentPath,
        )
        is ClientMessage.Authenticate -> authenticateElement(message)
        is ClientMessage.Event -> eventElement(message)
    }.toString()

    /**
     * Decodes a client message.
     *
     * @param text the wire text.
     * @return the decoded message.
     * @throws ConvexJsonException when the text is not a valid client message.
     */
    public fun decode(text: String): ClientMessage {
        val message = parseJsonObject(text)
        return when (val type = message.string("type")) {
            "Connect" -> decodeConnect(message)
            "ModifyQuerySet" -> decodeModifyQuerySet(message)
            "Mutation" -> decodeInvocation(message) { id, path, args, component ->
                ClientMessage.Mutation(id, path, args, component)
            }
            "Action" -> decodeInvocation(message) { id, path, args, component ->
                ClientMessage.Action(id, path, args, component)
            }
            "Authenticate" -> decodeAuthenticate(message)
            "Event" -> decodeEvent(message)
            else -> throw ConvexJsonException("unknown client message type '$type'")
        }
    }

    private fun connectElement(message: ClientMessage.Connect): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("Connect"),
            "sessionId" to JsonPrimitive(message.sessionId.value),
            "connectionCount" to JsonPrimitive(message.connectionCount.toLong()),
            "lastCloseReason" to JsonPrimitive(message.lastCloseReason),
        )
        message.maxObservedTimestamp?.let {
            fields["maxObservedTimestamp"] = JsonPrimitive(LittleEndianBase64.encodeULong(it.value))
        }
        message.clientTs?.let { fields["clientTs"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun modifyQuerySetElement(message: ClientMessage.ModifyQuerySet): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("ModifyQuerySet"),
            "baseVersion" to JsonPrimitive(message.baseVersion.value.toLong()),
            "newVersion" to JsonPrimitive(message.newVersion.value.toLong()),
            "modifications" to JsonArray(message.modifications.map(::modificationElement)),
        ),
    )

    private fun modificationElement(modification: QuerySetModification): JsonObject = when (modification) {
        is QuerySetModification.Add -> addQueryElement(modification.query)
        is QuerySetModification.Remove -> JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("Remove"),
                "queryId" to JsonPrimitive(modification.queryId.value.toLong()),
            ),
        )
    }

    private fun addQueryElement(query: Query): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("Add"),
            "queryId" to JsonPrimitive(query.queryId.value.toLong()),
            "udfPath" to JsonPrimitive(query.udfPath),
            "args" to JsonArray(query.args.map(ConvexJson::toJsonElement)),
        )
        query.journal?.let { fields["journal"] = JsonPrimitive(it) }
        query.componentPath?.let { fields["componentPath"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun invocationElement(
        type: String,
        requestId: RequestId,
        udfPath: String,
        args: List<ConvexValue>,
        componentPath: String?,
    ): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive(type),
            "requestId" to JsonPrimitive(requestId.value.toLong()),
            "udfPath" to JsonPrimitive(udfPath),
        )
        componentPath?.let { fields["componentPath"] = JsonPrimitive(it) }
        fields["args"] = JsonArray(args.map(ConvexJson::toJsonElement))
        return JsonObject(fields)
    }

    private fun authenticateElement(message: ClientMessage.Authenticate): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("Authenticate"),
            "baseVersion" to JsonPrimitive(message.baseVersion.value.toLong()),
        )
        when (val token = message.token) {
            is AuthenticationToken.Admin -> {
                fields["tokenType"] = JsonPrimitive("Admin")
                fields["value"] = JsonPrimitive(token.value)
                token.actingAs?.let { fields["actingAs"] = ConvexJson.toJsonElement(it) }
            }
            is AuthenticationToken.User -> {
                fields["tokenType"] = JsonPrimitive("User")
                fields["value"] = JsonPrimitive(token.value)
            }
            AuthenticationToken.None -> fields["tokenType"] = JsonPrimitive("None")
        }
        return JsonObject(fields)
    }

    private fun eventElement(message: ClientMessage.Event): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("Event"),
            "eventType" to JsonPrimitive(message.eventType),
            "event" to ConvexJson.toJsonElement(message.event),
        ),
    )

    private fun decodeConnect(message: JsonObject): ClientMessage.Connect = ClientMessage.Connect(
        sessionId = SessionId.parse(message.string("sessionId")),
        connectionCount = message.uint("connectionCount"),
        lastCloseReason = message.string("lastCloseReason"),
        maxObservedTimestamp = message.optionalString("maxObservedTimestamp")
            ?.let { Timestamp(LittleEndianBase64.decodeULong(it)) },
        clientTs = message.optionalLong("clientTs"),
    )

    private fun decodeModifyQuerySet(message: JsonObject): ClientMessage.ModifyQuerySet {
        val modifications = message.requireArray("modifications").map(::decodeModification)
        return ClientMessage.ModifyQuerySet(
            baseVersion = QuerySetVersion(message.uint("baseVersion")),
            newVersion = QuerySetVersion(message.uint("newVersion")),
            modifications = modifications,
        )
    }

    private fun decodeModification(element: JsonElement): QuerySetModification {
        val obj = element as? JsonObject ?: throw ConvexJsonException("modification must be an object")
        return when (val type = obj.string("type")) {
            "Add" -> QuerySetModification.Add(
                Query(
                    queryId = QueryId(obj.uint("queryId")),
                    udfPath = obj.string("udfPath"),
                    args = obj.optionalValueList("args"),
                    journal = obj.optionalString("journal"),
                    componentPath = obj.optionalString("componentPath"),
                ),
            )
            "Remove" -> QuerySetModification.Remove(QueryId(obj.uint("queryId")))
            else -> throw ConvexJsonException("unknown query set modification '$type'")
        }
    }

    private fun decodeInvocation(
        message: JsonObject,
        factory: (RequestId, String, List<ConvexValue>, String?) -> ClientMessage,
    ): ClientMessage = factory(
        RequestId(message.uint("requestId")),
        message.string("udfPath"),
        message.optionalValueList("args"),
        message.optionalString("componentPath"),
    )

    private fun decodeAuthenticate(message: JsonObject): ClientMessage.Authenticate {
        val token = when (val type = message.string("tokenType")) {
            "Admin" -> AuthenticationToken.Admin(
                value = message.string("value"),
                actingAs = message["actingAs"]?.let(ConvexJson::fromJsonElement),
            )
            "User" -> AuthenticationToken.User(message.string("value"))
            "None" -> AuthenticationToken.None
            else -> throw ConvexJsonException("unknown token type '$type'")
        }
        return ClientMessage.Authenticate(IdentityVersion(message.uint("baseVersion")), token)
    }

    private fun decodeEvent(message: JsonObject): ClientMessage.Event = ClientMessage.Event(
        eventType = message.string("eventType"),
        event = ConvexJson.fromJsonElement(
            message["event"] ?: throw ConvexJsonException("Event lacks event"),
        ),
    )
}
