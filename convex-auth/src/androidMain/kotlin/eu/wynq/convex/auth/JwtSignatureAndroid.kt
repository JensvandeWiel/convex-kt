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
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Android's `java.security` provides the same primitives as the JVM, so this is
 * a sibling of the JVM actual rather than shared code: the two source sets do
 * not see each other.
 */
internal actual fun verifySignature(
    algorithm: JwtAlgorithm,
    key: JsonWebKey,
    data: ByteArray,
    signature: ByteArray,
): Boolean = try {
    when (algorithm) {
        JwtAlgorithm.RS256 -> verifyRsa(key, data, signature)
        JwtAlgorithm.ES256 -> verifyEcdsa(key, data, signature)
    }
} catch (expected: GeneralSecurityException) {
    false
} catch (expected: IllegalArgumentException) {
    false
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64Url(segment: String?): ByteArray? =
    segment?.let {
        try {
            Base64.UrlSafe.decode(it)
        } catch (expected: IllegalArgumentException) {
            null
        }
    }

private fun verifyRsa(key: JsonWebKey, data: ByteArray, signature: ByteArray): Boolean {
    val modulus = decodeBase64Url(key.modulus) ?: return false
    val exponent = decodeBase64Url(key.exponent) ?: return false
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(
        RSAPublicKeySpec(BigInteger(1, modulus), BigInteger(1, exponent)),
    )
    val verifier = Signature.getInstance("SHA256withRSA")
    verifier.initVerify(publicKey)
    verifier.update(data)
    return verifier.verify(signature)
}

private fun verifyEcdsa(key: JsonWebKey, data: ByteArray, signature: ByteArray): Boolean {
    val x = decodeBase64Url(key.x) ?: return false
    val y = decodeBase64Url(key.y) ?: return false
    val parameters = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    val publicKey = KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), parameters),
    )
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(publicKey)
    verifier.update(data)
    return verifier.verify(joseToDer(signature))
}

private const val ES256_COORDINATE_BYTES = 32
private const val ES256_SIGNATURE_BYTES = 64
private const val DER_SEQUENCE_TAG: Byte = 0x30
private const val DER_INTEGER_TAG: Byte = 0x02

private fun joseToDer(signature: ByteArray): ByteArray {
    require(signature.size == ES256_SIGNATURE_BYTES) { "ES256 signature must be 64 bytes" }
    val r = derInteger(BigInteger(1, signature.copyOfRange(0, ES256_COORDINATE_BYTES)))
    val s = derInteger(BigInteger(1, signature.copyOfRange(ES256_COORDINATE_BYTES, ES256_SIGNATURE_BYTES)))
    val content = r + s
    return byteArrayOf(DER_SEQUENCE_TAG, content.size.toByte()) + content
}

private fun derInteger(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    return byteArrayOf(DER_INTEGER_TAG, bytes.size.toByte()) + bytes
}
