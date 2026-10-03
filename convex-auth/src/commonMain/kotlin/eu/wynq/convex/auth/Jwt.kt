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

import eu.wynq.convex.core.ConvexException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Signature algorithms accepted for a Convex authentication token.
 *
 * @property wireName the JOSE `alg` value.
 * @property keyType the JWK `kty` this algorithm requires.
 */
public enum class JwtAlgorithm(public val wireName: String, public val keyType: String) {
    /** RSASSA-PKCS1-v1_5 with SHA-256. */
    RS256("RS256", "RSA"),

    /** ECDSA over P-256 with SHA-256, with a JOSE raw signature. */
    ES256("ES256", "EC"),
    ;

    /** Resolution of the JOSE `alg` value. */
    public companion object {
        /**
         * Resolves the algorithm from its JWT `alg` value.
         *
         * @param name the `alg` value.
         * @return the algorithm, or `null` when unsupported.
         */
        public fun fromWire(name: String): JwtAlgorithm? = entries.firstOrNull { it.wireName == name }

        /**
         * Resolves the algorithm from an `alg` value, failing when unsupported.
         *
         * @param name the `alg` value.
         * @return the algorithm.
         */
        public fun require(name: String): JwtAlgorithm =
            fromWire(name) ?: throw ConvexJwtException("unsupported JWT algorithm '$name'")
    }
}

/**
 * Thrown when a token is structurally malformed or uses an unsupported feature.
 *
 * @property message why the token could not be used.
 * @property cause the underlying parse or decode failure, if any.
 */
public class ConvexJwtException(
    message: String,
    cause: Throwable? = null,
) : ConvexException(message, cause)

/**
 * A JSON Web Key, reduced to the fields Convex tokens need.
 *
 * @property keyType `RSA` or `EC`.
 * @property keyId the key identifier, matched against the token header.
 * @property algorithm the key's declared `alg`, if any.
 * @property curve the EC curve, for example `P-256`.
 * @property x the EC x coordinate, base64url.
 * @property y the EC y coordinate, base64url.
 * @property modulus the RSA modulus `n`, base64url.
 * @property exponent the RSA exponent `e`, base64url.
 */
public data class JsonWebKey(
    public val keyType: String,
    public val keyId: String? = null,
    public val algorithm: String? = null,
    public val curve: String? = null,
    public val x: String? = null,
    public val y: String? = null,
    public val modulus: String? = null,
    public val exponent: String? = null,
) {
    /** Parsing of JSON Web Keys. */
    public companion object {
        /**
         * Parses a JWK from a JSON object.
         *
         * @param element the JWK JSON.
         * @return the parsed key.
         * @throws ConvexJwtException when required fields are missing.
         */
        public fun parse(element: JsonElement): JsonWebKey {
            val obj = element as? JsonObject ?: throw ConvexJwtException("JWK must be a JSON object")
            val keyType = obj.stringOrNull("kty") ?: throw ConvexJwtException("JWK lacks 'kty'")
            return JsonWebKey(
                keyType = keyType,
                keyId = obj.stringOrNull("kid"),
                algorithm = obj.stringOrNull("alg"),
                curve = obj.stringOrNull("crv"),
                x = obj.stringOrNull("x"),
                y = obj.stringOrNull("y"),
                modulus = obj.stringOrNull("n"),
                exponent = obj.stringOrNull("e"),
            )
        }
    }
}

/**
 * The decoded JOSE header of a token.
 *
 * @property algorithm the declared signature algorithm, or `null` if unsupported.
 * @property keyId the `kid`, used to select a key.
 * @property type the `typ`, usually `JWT`.
 */
public data class JwtHeader(
    public val algorithm: JwtAlgorithm?,
    public val keyId: String?,
    public val type: String?,
)

/**
 * The decoded claims of a token.
 *
 * @property issuer the `iss` claim.
 * @property subject the `sub` claim.
 * @property audience the `aud` claim, taking the first value when it is a list.
 * @property expiresAtEpochSeconds the `exp` claim.
 * @property issuedAtEpochSeconds the `iat` claim.
 * @property notBeforeEpochSeconds the `nbf` claim.
 * @property raw every scalar claim, for fields this model does not name.
 */
public data class JwtClaims(
    public val issuer: String?,
    public val subject: String?,
    public val audience: String?,
    public val expiresAtEpochSeconds: Long?,
    public val issuedAtEpochSeconds: Long?,
    public val notBeforeEpochSeconds: Long?,
    public val raw: Map<String, String>,
)

/**
 * A parsed, unverified JSON Web Token.
 *
 * Verification is a separate step ([JwtVerifier]); parsing preserves the exact
 * signing input so the signature can be checked over the original bytes.
 *
 * @property header the JOSE header.
 * @property claims the payload claims.
 */
public class Jwt internal constructor(
    public val header: JwtHeader,
    public val claims: JwtClaims,
    internal val signingInput: ByteArray,
    internal val signature: ByteArray,
) {
    /**
     * Whether the token is expired at [nowEpochSeconds].
     *
     * @param nowEpochSeconds the current time.
     * @return `true` when there is an `exp` claim and it is not in the future.
     */
    public fun isExpired(nowEpochSeconds: Long): Boolean =
        claims.expiresAtEpochSeconds?.let { nowEpochSeconds >= it } ?: false

    /** Parsing of compact JWS tokens. */
    public companion object {
        /**
         * Parses a compact JWS without verifying it.
         *
         * @param token the `header.payload.signature` text.
         * @return the parsed token.
         * @throws ConvexJwtException when malformed.
         */
        public fun parse(token: String): Jwt {
            val parts = token.split('.')
            if (parts.size != JWT_PARTS) throw ConvexJwtException("JWT must have $JWT_PARTS parts")
            val header = decodeObject(parts[0], "header")
            val claims = decodeObject(parts[1], "payload")
            val signingInput = "${parts[0]}.${parts[1]}".encodeToByteArray()
            return Jwt(
                header = parseHeader(header),
                claims = parseClaims(claims),
                signingInput = signingInput,
                signature = decodeBase64Url(parts[2]),
            )
        }

        private fun decodeBase64Url(text: String): ByteArray =
            decodeBase64UrlOrNull(text)
                ?: throw ConvexJwtException("invalid base64url segment")

        private fun decodeObject(segment: String, label: String): JsonObject {
            val text = decodeBase64Url(segment).decodeToString()
            return try {
                Json.parseToJsonElement(text).jsonObject
            } catch (failure: SerializationException) {
                throw ConvexJwtException("JWT $label is not valid JSON: ${failure.message}", failure)
            }
        }

        private fun parseHeader(header: JsonObject): JwtHeader = JwtHeader(
            algorithm = header.stringOrNull("alg")?.let(JwtAlgorithm::fromWire),
            keyId = header.stringOrNull("kid"),
            type = header.stringOrNull("typ"),
        )

        private fun parseClaims(claims: JsonObject): JwtClaims = JwtClaims(
            issuer = claims.stringOrNull("iss"),
            subject = claims.stringOrNull("sub"),
            audience = claims.audienceOrNull(),
            expiresAtEpochSeconds = claims.longOrNull("exp"),
            issuedAtEpochSeconds = claims.longOrNull("iat"),
            notBeforeEpochSeconds = claims.longOrNull("nbf"),
            raw = claims.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.content?.let { key to it }
            }.toMap(),
        )
    }
}

private const val JWT_PARTS = 3

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { primitive -> primitive.content.toLongOrNull() }

private fun JsonObject.audienceOrNull(): String? = when (val value = this["aud"]) {
    is JsonPrimitive -> if (value.isString) value.content else null
    is JsonArray -> value.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
    else -> null
}
