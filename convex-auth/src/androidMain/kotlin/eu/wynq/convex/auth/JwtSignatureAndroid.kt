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

/**
 * Android's signature primitive, backed by `java.security` like the JVM one.
 *
 * Only the framework calls live here; the DER encoding both backends need is
 * shared from common code so the two actuals cannot diverge.
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
    return verifier.verify(joseToDer(signature) ?: return false)
}
