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
package eu.wynq.convex.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers JWT parsing and claim extraction. Signature verification is exercised
 * in the JVM test set, where a key can be generated.
 */
@OptIn(ExperimentalEncodingApi::class)
class JwtParsingTest {

    private val token = token(
        header = """{"alg":"ES256","kid":"k1","typ":"JWT"}""",
        payload = """{"iss":"https://example.convex.cloud","sub":"user_1","aud":"convex","exp":4102444800,"iat":1600000000}""",
    )

    @Test
    fun parsesHeaderAndClaims() {
        val jwt = Jwt.parse(token)
        assertEquals(JwtAlgorithm.ES256, jwt.header.algorithm)
        assertEquals("k1", jwt.header.keyId)
        assertEquals("JWT", jwt.header.type)
        assertEquals("https://example.convex.cloud", jwt.claims.issuer)
        assertEquals("user_1", jwt.claims.subject)
        assertEquals("convex", jwt.claims.audience)
        assertEquals(4_102_444_800L, jwt.claims.expiresAtEpochSeconds)
        assertEquals(1_600_000_000L, jwt.claims.issuedAtEpochSeconds)
    }

    @Test
    fun reportsExpiry() {
        val jwt = Jwt.parse(token)
        assertFalse(jwt.isExpired(1_600_000_000L))
        assertTrue(jwt.isExpired(4_102_444_800L))
    }

    @Test
    fun reportsUnknownAlgorithmAsNull() {
        val jwt = Jwt.parse(token(header = """{"alg":"none"}""", payload = """{"sub":"x"}"""))
        assertNull(jwt.header.algorithm)
    }

    @Test
    fun readsAudienceFromAnArray() {
        val jwt = Jwt.parse(token(payload = """{"aud":["convex","other"]}"""))
        assertEquals("convex", jwt.claims.audience)
    }

    @Test
    fun rejectsMalformedTokens() {
        assertFailsWith<ConvexJwtException> { Jwt.parse("not.a.jwt.at.all") }
        assertFailsWith<ConvexJwtException> { Jwt.parse("only-one-part") }
    }

    @Test
    fun parsesJsonWebKey() {
        val key = JsonWebKey.parse(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"kty":"EC","crv":"P-256","kid":"k1","x":"abc","y":"def","alg":"ES256"}""",
            ),
        )
        assertEquals("EC", key.keyType)
        assertEquals("P-256", key.curve)
        assertEquals("k1", key.keyId)
    }

    private fun token(
        header: String = """{"alg":"ES256","kid":"k1","typ":"JWT"}""",
        payload: String = """{"iss":"https://example.convex.cloud","sub":"user_1","aud":"convex","exp":4102444800,"iat":1600000000}""",
        signature: ByteArray = byteArrayOf(1, 2, 3),
    ): String =
        Base64.UrlSafe.encode(header.encodeToByteArray()) + "." +
            Base64.UrlSafe.encode(payload.encodeToByteArray()) + "." +
            Base64.UrlSafe.encode(signature)
}
