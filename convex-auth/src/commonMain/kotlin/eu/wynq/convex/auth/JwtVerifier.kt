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

/** The outcome of verifying a token. */
public sealed interface JwtVerificationResult {
    /**
     * The token is well-formed, unexpired, and its signature checks out.
     *
     * @property token the parsed token.
     */
    public data class Valid(public val token: Jwt) : JwtVerificationResult

    /**
     * The token was rejected.
     *
     * @property reason a human-readable cause.
     */
    public data class Invalid(public val reason: String) : JwtVerificationResult
}

/**
 * Verifies Convex authentication tokens.
 *
 * Verification is explicit about time: the caller supplies the current epoch
 * seconds, so the result is deterministic and testable and the library needs no
 * clock or `kotlinx-datetime` dependency.
 */
public object JwtVerifier {
    /**
     * Verifies [token] against [keys] at [nowEpochSeconds].
     *
     * A key is selected by `kid` when the header carries one, otherwise any key
     * of the matching type (and, when the key declares an `alg`, a matching one)
     * is tried.
     *
     * @param token the compact JWS.
     * @param keys candidate public keys.
     * @param nowEpochSeconds the current time, for the `exp` claim.
     * @return [JwtVerificationResult.Valid] or [JwtVerificationResult.Invalid].
     */
    public fun verify(
        token: String,
        keys: List<JsonWebKey>,
        nowEpochSeconds: Long,
    ): JwtVerificationResult {
        val jwt = try {
            Jwt.parse(token)
        } catch (failure: ConvexJwtException) {
            return JwtVerificationResult.Invalid(failure.message ?: "malformed token")
        }
        return validate(jwt, keys, nowEpochSeconds)
    }

    private fun validate(
        jwt: Jwt,
        keys: List<JsonWebKey>,
        nowEpochSeconds: Long,
    ): JwtVerificationResult {
        val algorithm = jwt.header.algorithm
            ?: return JwtVerificationResult.Invalid("missing or unsupported 'alg'")
        if (jwt.isExpired(nowEpochSeconds)) {
            return JwtVerificationResult.Invalid("token expired")
        }
        val candidates = selectKeys(keys, jwt.header.keyId, algorithm)
        if (candidates.isEmpty()) {
            return JwtVerificationResult.Invalid("no key matches the token")
        }
        val verified = candidates.any { key ->
            verifySignature(algorithm, key, jwt.signingInput, jwt.signature)
        }
        return if (verified) {
            JwtVerificationResult.Valid(jwt)
        } else {
            JwtVerificationResult.Invalid("signature does not verify")
        }
    }

    private fun selectKeys(
        keys: List<JsonWebKey>,
        keyId: String?,
        algorithm: JwtAlgorithm,
    ): List<JsonWebKey> {
        val byType = keys.filter { it.keyType == algorithm.keyType }
        if (keyId != null) {
            val byId = byType.filter { it.keyId == keyId }
            if (byId.isNotEmpty()) return byId
        }
        return byType.filter { it.algorithm == null || it.algorithm == algorithm.wireName }
    }
}
