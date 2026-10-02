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

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Verifies signed tokens end to end on the JVM, where keys can be generated.
 * These are the tests that prove RS256 and ES256 actually verify, rather than
 * only that parsing works.
 */
@OptIn(ExperimentalEncodingApi::class)
class JwtVerifierTest {

    private val now = 1_700_000_000L

    @Test
    fun verifiesRs256() {
        val pair = rsaKeyPair()
        val token = sign(pair, JwtAlgorithm.RS256, keyId = "r1", expiresAt = now + 60)
        val result = JwtVerifier.verify(token, listOf(rsaJwk(pair, "r1")), now)
        assertIs<JwtVerificationResult.Valid>(result)
    }

    @Test
    fun verifiesEs256() {
        val pair = ecKeyPair()
        val token = sign(pair, JwtAlgorithm.ES256, keyId = "e1", expiresAt = now + 60)
        val result = JwtVerifier.verify(token, listOf(ecJwk(pair, "e1")), now)
        assertIs<JwtVerificationResult.Valid>(result)
    }

    @Test
    fun rejectsTamperedPayload() {
        val pair = rsaKeyPair()
        val token = sign(pair, JwtAlgorithm.RS256, keyId = "r1", expiresAt = now + 60)
        val parts = token.split('.')
        val tamperedHeader = base64Url("""{"alg":"RS256","kid":"r1"}""")
        val tamperedPayload = base64Url("""{"sub":"attacker","exp":${now + 60}}""")
        val tampered = "$tamperedHeader.$tamperedPayload.${parts[2]}"
        assertIs<JwtVerificationResult.Invalid>(JwtVerifier.verify(tampered, listOf(rsaJwk(pair, "r1")), now))
    }

    @Test
    fun rejectsExpiredToken() {
        val pair = rsaKeyPair()
        val token = sign(pair, JwtAlgorithm.RS256, keyId = "r1", expiresAt = now - 1)
        assertIs<JwtVerificationResult.Invalid>(JwtVerifier.verify(token, listOf(rsaJwk(pair, "r1")), now))
    }

    @Test
    fun rejectsWrongKey() {
        val signer = rsaKeyPair()
        val other = rsaKeyPair()
        val token = sign(signer, JwtAlgorithm.RS256, keyId = "r1", expiresAt = now + 60)
        assertIs<JwtVerificationResult.Invalid>(JwtVerifier.verify(token, listOf(rsaJwk(other, "r1")), now))
    }

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(pair: KeyPair, algorithm: JwtAlgorithm, keyId: String, expiresAt: Long): String {
        val header = base64Url("""{"alg":"${algorithm.wireName}","kid":"$keyId"}""")
        val payload = base64Url("""{"sub":"user_1","exp":$expiresAt}""")
        val signingInput = "$header.$payload"
        val rawSignature = when (algorithm) {
            JwtAlgorithm.RS256 -> rsaSign(pair, signingInput)
            JwtAlgorithm.ES256 -> derToJose(ecSign(pair, signingInput))
        }
        return "$signingInput.${base64Url(rawSignature)}"
    }

    private fun rsaSign(pair: KeyPair, input: String): ByteArray =
        Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(input.encodeToByteArray())
        }.sign()

    private fun ecSign(pair: KeyPair, input: String): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private)
            update(input.encodeToByteArray())
        }.sign()

    private fun rsaJwk(pair: KeyPair, keyId: String): JsonWebKey {
        val publicKey = pair.public as RSAPublicKey
        return JsonWebKey(
            keyType = "RSA",
            keyId = keyId,
            algorithm = "RS256",
            modulus = base64Url(publicKey.modulus.unsignedBytes()),
            exponent = base64Url(publicKey.publicExponent.unsignedBytes()),
        )
    }

    private fun ecJwk(pair: KeyPair, keyId: String): JsonWebKey {
        val publicKey = pair.public as ECPublicKey
        return JsonWebKey(
            keyType = "EC",
            keyId = keyId,
            algorithm = "ES256",
            curve = "P-256",
            x = base64Url(publicKey.w.affineX.unsignedBytes()),
            y = base64Url(publicKey.w.affineY.unsignedBytes()),
        )
    }

    private fun BigInteger.unsignedBytes(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    /** Parses the DER `SEQUENCE { INTEGER r, INTEGER s }` into JOSE `r || s`. */
    private fun derToJose(der: ByteArray): ByteArray {
        var index = 0
        require(der[index++].toInt() == DER_SEQUENCE) { "expected SEQUENCE" }
        index++ // short-form length
        val r = readInteger(der, index)
        index = r.second
        val s = readInteger(der, index)
        return leftPad32(r.first) + leftPad32(s.first)
    }

    private fun readInteger(der: ByteArray, start: Int): Pair<ByteArray, Int> {
        var index = start
        require(der[index++].toInt() == DER_INTEGER) { "expected INTEGER" }
        val length = der[index++].toInt() and 0xFF
        val value = der.copyOfRange(index, index + length)
        return value to (index + length)
    }

    private fun leftPad32(bytes: ByteArray): ByteArray {
        val stripped = if (bytes.size > 32 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        val output = ByteArray(32)
        stripped.copyInto(output, 32 - stripped.size)
        return output
    }

    private fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes)

    private fun base64Url(text: String): String = Base64.UrlSafe.encode(text.encodeToByteArray())

    private companion object {
        private const val DER_SEQUENCE = 0x30
        private const val DER_INTEGER = 0x02
    }
}
