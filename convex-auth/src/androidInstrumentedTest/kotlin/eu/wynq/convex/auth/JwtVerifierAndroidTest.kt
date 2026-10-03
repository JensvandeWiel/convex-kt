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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the frozen JWT vectors on a real Android device.
 *
 * The shared `JwtVerifierVectorsTest` runs as an Android *unit* test, on the
 * host JVM and its crypto provider. This test drives the same `androidMain`
 * actual against the device's provider instead, which is the only place the
 * Android `java.security` backend is exercised as Android ships it.
 *
 * The vectors are copied verbatim from that test so the two stay comparable.
 */
@RunWith(AndroidJUnit4::class)
class JwtVerifierAndroidTest {

    private val now = 1_700_000_000L

    @Test
    fun verifiesRs256VectorOnDevice() {
        val result = JwtVerifier.verify(RS256_TOKEN, listOf(rsaKey), now)
        assertTrue(
            "expected a valid RS256 token, got ${(result as? JwtVerificationResult.Invalid)?.reason}",
            result is JwtVerificationResult.Valid,
        )
    }

    @Test
    fun verifiesEs256VectorOnDevice() {
        val result = JwtVerifier.verify(ES256_TOKEN, listOf(ecKey), now)
        assertTrue(
            "expected a valid ES256 token, got ${(result as? JwtVerificationResult.Invalid)?.reason}",
            result is JwtVerificationResult.Valid,
        )
    }

    @Test
    fun rejectsTokenSignedByAnotherKeyOnDevice() {
        val result = JwtVerifier.verify(RS256_TOKEN, listOf(ecKey), now)
        assertTrue("expected a mismatch to be rejected", result is JwtVerificationResult.Invalid)
    }

    private val rsaKey = JsonWebKey(
        keyType = "RSA",
        keyId = "r1",
        algorithm = "RS256",
        modulus = RS256_N,
        exponent = RS256_E,
    )

    private val ecKey = JsonWebKey(
        keyType = "EC",
        keyId = "e1",
        algorithm = "ES256",
        curve = "P-256",
        x = ES256_X,
        y = ES256_Y,
    )

    private companion object {
        const val RS256_N =
            "rL2jFGlyj7iwM-IeE7E8UQTzgw6-9vKoPidunuOQvNA1IBPZoImUIr0CQqQW-Txh6VVDMZDFmlLIW_iMB_Q2M6TtPAls" +
                "NKjVgCElHJemJnW-zn1uHW938VWkqSCgOEvS-SNkeEjXgUCcc_q_WjoMZsO8xVopKTQKa6QVcQFwjH--CxhFEIw48ciU" +
                "-l-9o1leUU2wTqs7YAg4_QWMR4hXp9jVeCkhJ9OQqtqVHqu4UMJKGWKl5X104WaeKU5Y7KsIAHsXR8bLWGxAMTNvaD_" +
                "CRXBtgpxtI5wYpUA7rz-ZfExnl8Nt99a3-__StK5F41VGduKGGT6DL9FLaIdd3uQ0nw"
        const val RS256_E = "AQAB"
        const val ES256_X = "ro8UDM7SW5Vg1oi_Yz33uI2Pp2MrwlQKqca8xipC2p4"
        const val ES256_Y = "TD7NEDdjrNhm8CJjmemUzEtOjX9h6Qii1sSNvm_kcLE"

        const val RS256_TOKEN =
            "eyJhbGciOiJSUzI1NiIsImtpZCI6InIxIn0.eyJzdWIiOiJ1c2VyXzEiLCJleHAiOjE3MDAwMDM2MDB9." +
                "Anj9IhMnkEsCs3Gfm5_fpa5XfiASygtXqMGO8nVeGHZ4FJWhUb4ICjmzd8YFJIicsOBaTyZfWo6GinNEn8IWzfboVb" +
                "FQi6BgWptImxMYOZySUutHPq8VINTQxK3WVUtMyCrCpi1fYVWxS8HI-60fqKtq5-gdJCQ_UlmSMw0K5Fb0AkIuozdkk" +
                "A6bqtdCVCtQUfj83_NPdQcrFaCfJD2u_u6RN-W6qP7Sh3X_iM5g8CBlaVNPSNP66k7r48T9jSt0NblTER8Bciz0LqM" +
                "RNT_jru8vpOKw1q4qXR5BfIrrQ3AdeDDWxmKqiPFywpSVxUX2ch1Ae3Xm8X2att5rD1yLnQ"

        const val ES256_TOKEN =
            "eyJhbGciOiJFUzI1NiIsImtpZCI6ImUxIn0.eyJzdWIiOiJ1c2VyXzEiLCJleHAiOjE3MDAwMDM2MDB9." +
                "IKr5QV3XOe6rde7kJj7dsX1DOq-ROY1NjBy0uKn5ZfZHkrgRuwczZbpbBtKkba1MWLpAvPeQT9SXPmxTWP9ZqA"
    }
}
