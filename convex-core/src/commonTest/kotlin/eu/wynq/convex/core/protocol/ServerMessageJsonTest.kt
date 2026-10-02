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

import eu.wynq.convex.core.value.ConvexJsonException
import eu.wynq.convex.core.value.ConvexValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Covers the server-message wire codec. `Ping` and `FatalError` are asserted
 * against the recorded conformance fixtures; the rest are round-tripped and a
 * few exact shapes are pinned to catch field-naming regressions.
 */
class ServerMessageJsonTest {

    @Test
    fun decodesRecordedPingFrame() {
        assertEquals(ServerMessage.Ping, ServerMessageJson.decode("""{"type":"Ping"}"""))
    }

    @Test
    fun decodesRecordedFatalErrorFrame() {
        val text = """{"type":"FatalError","error":"Received Invalid JSON on websocket"}"""
        val decoded = assertIs<ServerMessage.FatalError>(ServerMessageJson.decode(text))
        assertEquals("Received Invalid JSON on websocket", decoded.error)
    }

    @Test
    fun encodesPingAndFatalErrorExactly() {
        assertEquals("""{"type":"Ping"}""", ServerMessageJson.encode(ServerMessage.Ping))
        assertEquals(
            """{"type":"FatalError","error":"boom"}""",
            ServerMessageJson.encode(ServerMessage.FatalError("boom")),
        )
    }

    @Test
    fun transitionRoundTripsWithAllModificationKinds() {
        val message = ServerMessage.Transition(
            startVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(1u)),
            endVersion = StateVersion(QuerySetVersion(1u), IdentityVersion(0u), Timestamp(2u)),
            modifications = listOf(
                StateModification.QueryUpdated(
                    queryId = QueryId(0u),
                    value = ConvexValue.Int64(7),
                    logLines = listOf("hello"),
                    journal = null,
                ),
                StateModification.QueryFailed(
                    queryId = QueryId(1u),
                    errorMessage = "nope",
                    logLines = emptyList(),
                    journal = "j",
                    errorData = ConvexValue.String("payload"),
                ),
                StateModification.QueryRemoved(QueryId(2u)),
            ),
            clientClockSkew = -5L,
            serverTs = Timestamp(ULong.MAX_VALUE),
        )
        assertEquals(message, ServerMessageJson.decode(ServerMessageJson.encode(message)))
    }

    @Test
    fun queryFailedDistinguishesAbsentFromNullErrorData() {
        val absent = StateModification.QueryFailed(
            queryId = QueryId(0u),
            errorMessage = "m",
            logLines = emptyList(),
            journal = null,
            errorData = null,
        )
        val presentNull = absent.copy(errorData = ConvexValue.Null)

        val absentRoundTripped = roundTripModification(absent)
        val nullRoundTripped = roundTripModification(presentNull)

        assertEquals(null, (absentRoundTripped as StateModification.QueryFailed).errorData)
        assertEquals(ConvexValue.Null, (nullRoundTripped as StateModification.QueryFailed).errorData)
    }

    @Test
    fun mutationResponseSuccessRoundTrips() {
        val message = ServerMessage.MutationResponse(
            requestId = RequestId(3u),
            result = ConvexResult.Success(ConvexValue.Int64(42)),
            ts = Timestamp(99u),
            logLines = listOf("logged"),
        )
        assertEquals(message, ServerMessageJson.decode(ServerMessageJson.encode(message)))
    }

    @Test
    fun mutationResponseMessageFailureHasNoErrorDataKey() {
        val message = ServerMessage.MutationResponse(
            requestId = RequestId(3u),
            result = ConvexResult.Failure(ErrorPayload.Message("bad")),
            ts = null,
            logLines = emptyList(),
        )
        val encoded = ServerMessageJson.encode(message)
        assertEquals(false, encoded.contains("errorData"))
        val decoded = ServerMessageJson.decode(encoded) as ServerMessage.MutationResponse
        assertEquals(ErrorPayload.Message("bad"), (decoded.result as ConvexResult.Failure).error)
    }

    @Test
    fun mutationResponseErrorDataFailureRoundTrips() {
        val message = ServerMessage.MutationResponse(
            requestId = RequestId(4u),
            result = ConvexResult.Failure(ErrorPayload.ErrorData("bad", ConvexValue.String("detail"))),
            ts = Timestamp(1u),
            logLines = emptyList(),
        )
        val decoded = ServerMessageJson.decode(ServerMessageJson.encode(message))
        assertEquals(message, decoded)
    }

    @Test
    fun actionResponseRoundTripsWithoutTimestamp() {
        val message = ServerMessage.ActionResponse(
            requestId = RequestId(1u),
            result = ConvexResult.Success(ConvexValue.Boolean(true)),
            logLines = emptyList(),
        )
        val encoded = ServerMessageJson.encode(message)
        assertEquals(false, encoded.contains("\"ts\""))
        assertEquals(message, ServerMessageJson.decode(encoded))
    }

    @Test
    fun authErrorRoundTripsWithAndWithoutUpdateFlag() {
        val base = ServerMessage.AuthError("expired", IdentityVersion(2u), null)
        val withFlag = base.copy(authUpdateAttempted = true)
        assertEquals(base, ServerMessageJson.decode(ServerMessageJson.encode(base)))
        assertEquals(withFlag, ServerMessageJson.decode(ServerMessageJson.encode(withFlag)))
    }

    @Test
    fun transitionChunkRoundTrips() {
        val message = ServerMessage.TransitionChunk(
            chunk = "abc",
            partNumber = 0u,
            totalParts = 3u,
            transitionId = "t1",
        )
        assertEquals(message, ServerMessageJson.decode(ServerMessageJson.encode(message)))
    }

    @Test
    fun unknownTypeIsRejected() {
        assertFailsWith<ConvexJsonException> { ServerMessageJson.decode("""{"type":"Nope"}""") }
    }

    private fun roundTripModification(modification: StateModification): StateModification {
        val transition = ServerMessage.Transition(
            startVersion = StateVersion(QuerySetVersion(0u), IdentityVersion(0u), Timestamp(0u)),
            endVersion = StateVersion(QuerySetVersion(1u), IdentityVersion(0u), Timestamp(1u)),
            modifications = listOf(modification),
            clientClockSkew = null,
            serverTs = null,
        )
        val decoded = ServerMessageJson.decode(ServerMessageJson.encode(transition)) as ServerMessage.Transition
        return decoded.modifications.single()
    }
}
