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

import eu.wynq.convex.core.identity.UserIdentifier
import eu.wynq.convex.core.identity.UserIdentityAttributes
import eu.wynq.convex.core.value.ConvexJsonException
import eu.wynq.convex.core.value.ConvexValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the client-message wire codec. The `Connect` expectations are taken
 * from the recorded conformance fixture so a regression in field naming or
 * omission rules fails here rather than at the backend.
 */
class ClientMessageJsonTest {

    private val sessionId = "e432f8a8-d59b-4b75-906e-fe141a1cdcfe"

    @Test
    fun connectOmitsAbsentOptionalFields() {
        val message = ClientMessage.Connect(
            sessionId = SessionId.parse(sessionId),
            connectionCount = 0u,
            lastCloseReason = "InitialConnect",
        )
        assertEquals(
            """{"type":"Connect","sessionId":"$sessionId","connectionCount":0,"lastCloseReason":"InitialConnect"}""",
            ClientMessageJson.encode(message),
        )
    }

    @Test
    fun connectEncodesTimestampAndClientClock() {
        val message = ClientMessage.Connect(
            sessionId = SessionId.parse(sessionId),
            connectionCount = 3u,
            lastCloseReason = "InitialConnect",
            maxObservedTimestamp = Timestamp(ULong.MAX_VALUE),
            clientTs = 1_700_000_000_000L,
        )
        assertEquals(
            """{"type":"Connect","sessionId":"$sessionId","connectionCount":3,"lastCloseReason":"InitialConnect",""" +
                """"maxObservedTimestamp":"//////////8=","clientTs":1700000000000}""",
            ClientMessageJson.encode(message),
        )
    }

    @Test
    fun decodesRecordedConnectFrameIncludingNulls() {
        // The backend accepts explicit nulls for the optional fields, so the
        // decoder must tolerate them even though we never emit them.
        val text =
            """{"type":"Connect","sessionId":"$sessionId","connectionCount":0,""" +
                """"lastCloseReason":"InitialConnect","maxObservedTimestamp":null,"clientTs":null}"""
        val decoded = ClientMessageJson.decode(text) as ClientMessage.Connect
        assertEquals(SessionId.parse(sessionId), decoded.sessionId)
        assertEquals(0u, decoded.connectionCount)
        assertEquals("InitialConnect", decoded.lastCloseReason)
        assertEquals(null, decoded.maxObservedTimestamp)
        assertEquals(null, decoded.clientTs)
    }

    @Test
    fun connectRejectsConnectFrameWithoutLastCloseReason() {
        // The encoder always sends `lastCloseReason`, so an absent key is a
        // wire mismatch, not a defaultable omission.
        val text = """{"type":"Connect","sessionId":"$sessionId","connectionCount":1}"""
        assertFailsWith<ConvexJsonException> {
            ClientMessageJson.decode(text)
        }
    }

    @Test
    fun modifyQuerySetRoundTripsWithAddAndRemove() {
        val message = ClientMessage.ModifyQuerySet(
            baseVersion = QuerySetVersion(0u),
            newVersion = QuerySetVersion(1u),
            modifications = listOf(
                QuerySetModification.Add(
                    Query(
                        queryId = QueryId(0u),
                        udfPath = "messages:list",
                        args = listOf(ConvexValue.Int64(7)),
                    ),
                ),
                QuerySetModification.Remove(QueryId(2u)),
            ),
        )
        assertEquals(message, ClientMessageJson.decode(ClientMessageJson.encode(message)))
    }

    @Test
    fun mutationRoundTripsWithArgs() {
        val message = ClientMessage.Mutation(
            requestId = RequestId(4u),
            udfPath = "messages:send",
            args = listOf(ConvexValue.String("hi"), ConvexValue.Bytes(byteArrayOf(1, 2))),
        )
        assertEquals(message, ClientMessageJson.decode(ClientMessageJson.encode(message)))
    }

    @Test
    fun actionRoundTrips() {
        val message = ClientMessage.Action(
            requestId = RequestId(9u),
            udfPath = "tasks:run",
            args = emptyList(),
            componentPath = "child",
        )
        assertEquals(message, ClientMessageJson.decode(ClientMessageJson.encode(message)))
    }

    @Test
    fun authenticateRoundTripsAllTokenKinds() {
        val tokens = listOf(
            AuthenticationToken.Admin("key"),
            AuthenticationToken.Admin("key", UserIdentityAttributes(UserIdentifier("user1"))),
            AuthenticationToken.User("jwt"),
            AuthenticationToken.None,
        )
        tokens.forEach { token ->
            val message = ClientMessage.Authenticate(IdentityVersion(1u), token)
            assertEquals(message, ClientMessageJson.decode(ClientMessageJson.encode(message)))
        }
    }

    @Test
    fun adminImpersonationUsesImpersonatingKey() {
        // The admin payload is not `actingAs`: convex-js writes `impersonating`,
        // which the backend accepts via the serde alias on `acting_as`.
        val actingAs = UserIdentityAttributes(UserIdentifier("issuer|subject"), name = "Barbara Liskov")
        val message = ClientMessage.Authenticate(
            IdentityVersion(0u),
            AuthenticationToken.Admin("key", actingAs),
        )
        val encoded = ClientMessageJson.encode(message)
        assertTrue(encoded.contains("\"impersonating\""), "got: $encoded")
        assertEquals(message, ClientMessageJson.decode(encoded))
    }

    @Test
    fun adminImpersonationAcceptsActingAsSpelling() {
        // convex-rs serializes the field as `acting_as`; accept it too.
        val text = """
            {"type":"Authenticate","baseVersion":0,"tokenType":"Admin","value":"key",
             "acting_as":{"tokenIdentifier":"issuer|subject","name":"Barbara Liskov"}}
        """.trimIndent()
        val expected = AuthenticationToken.Admin(
            "key",
            UserIdentityAttributes(UserIdentifier("issuer|subject"), name = "Barbara Liskov"),
        )
        val decoded = assertIs<ClientMessage.Authenticate>(ClientMessageJson.decode(text))
        assertEquals(expected, decoded.token)
    }

    @Test
    fun authenticateUsesFlattenedTokenType() {
        val message = ClientMessage.Authenticate(IdentityVersion(0u), AuthenticationToken.User("jwt"))
        assertEquals(
            """{"type":"Authenticate","baseVersion":0,"tokenType":"User","value":"jwt"}""",
            ClientMessageJson.encode(message),
        )
    }

    @Test
    fun eventRoundTrips() {
        val message = ClientMessage.Event("pageView", ConvexValue.String("/home"))
        assertEquals(message, ClientMessageJson.decode(ClientMessageJson.encode(message)))
    }

    @Test
    fun unknownTypeIsRejected() {
        assertFailsWith<ConvexJsonException> { ClientMessageJson.decode("""{"type":"Nope"}""") }
    }

    @Test
    fun decodesLegacyAdminAuthenticate() {
        // The upstream `authentication_token_backwards_compatability` shape:
        // an Admin token with a bare value.
        val text = """{"type":"Authenticate","tokenType":"Admin","value":"legacy-key","baseVersion":0}"""
        val decoded = assertIs<ClientMessage.Authenticate>(ClientMessageJson.decode(text))
        assertEquals(AuthenticationToken.Admin("legacy-key"), decoded.token)
        assertEquals(0u, decoded.baseVersion.value)
    }

    @Test
    fun sessionIdMustBeAValidUuid() {
        assertFailsWith<IllegalArgumentException> { SessionId.parse("c0ffee") }
        assertEquals(sessionId, SessionId.parse(sessionId.uppercase()).value)
    }

    @Test
    fun randomSessionIdIsAValidUuid() {
        repeat(8) {
            val generated = SessionId.random().value
            assertEquals(36, generated.length)
            assertEquals(generated, SessionId.parse(generated).value)
            assertTrue(generated[14] == '4', "expected a version 4 UUID, got $generated")
        }
    }
}
