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
package eu.wynq.convex.core.identity

import eu.wynq.convex.core.value.ConvexJsonException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlin.jvm.JvmInline

/**
 * A stable identifier for a user, derived from the JWT issuer and subject as
 * `"{issuer}|{subject}"`.
 *
 * Mirrors `convex-rs`'s `sync_types/src/types/mod.rs::UserIdentifier`. Equality
 * is by the composed string.
 *
 * @property value the composed `issuer|subject` text.
 */
@JvmInline
public value class UserIdentifier(public val value: String) {
    /** Construction helpers. */
    public companion object {
        /**
         * Composes an identifier from a JWT issuer and subject.
         *
         * @param issuerName the token issuer.
         * @param subject the token subject.
         * @return the composed identifier.
         */
        public fun construct(issuerName: String, subject: String): UserIdentifier =
            UserIdentifier("$issuerName|$subject")
    }

    /**
     * Returns the composed identifier text.
     *
     * @return [value].
     */
    public override fun toString(): String = value
}

/**
 * The OpenID Connect profile attributes the backend associates with a user.
 *
 * Mirrors `convex-rs`'s `sync_types/src/types/mod.rs::UserIdentityAttributes`.
 * The many optional fields are the standard OIDC claims; [customClaims] holds
 * any key the backend did not recognise, each value kept as its raw JSON text
 * so an unknown claim survives a round trip verbatim.
 *
 * @property tokenIdentifier the composed user identifier.
 * @property issuer the token issuer, when present.
 * @property subject the token subject, when present.
 * @property name the full name.
 * @property givenName the given name.
 * @property familyName the family name.
 * @property nickname the nickname.
 * @property preferredUsername the preferred username.
 * @property profileUrl the profile URL.
 * @property pictureUrl the picture URL.
 * @property websiteUrl the website URL.
 * @property email the email address.
 * @property emailVerified whether the email is verified.
 * @property gender the gender.
 * @property birthday the birthday.
 * @property timezone the timezone.
 * @property language the language.
 * @property phoneNumber the phone number.
 * @property phoneNumberVerified whether the phone number is verified.
 * @property address the postal address.
 * @property updatedAt the last update time, as RFC 3339 text.
 * @property customClaims unrecognised claims, keyed by name, each value raw JSON.
 */
// The upstream struct has this many independent optional claims; grouping them
// into nested objects would invent a shape the wire does not have. The count is
// the data model, not a parameter-list smell.
@Suppress("LongParameterList")
public data class UserIdentityAttributes(
    public val tokenIdentifier: UserIdentifier,
    public val issuer: String? = null,
    public val subject: String? = null,
    public val name: String? = null,
    public val givenName: String? = null,
    public val familyName: String? = null,
    public val nickname: String? = null,
    public val preferredUsername: String? = null,
    public val profileUrl: String? = null,
    public val pictureUrl: String? = null,
    public val websiteUrl: String? = null,
    public val email: String? = null,
    public val emailVerified: Boolean? = null,
    public val gender: String? = null,
    public val birthday: String? = null,
    public val timezone: String? = null,
    public val language: String? = null,
    public val phoneNumber: String? = null,
    public val phoneNumberVerified: Boolean? = null,
    public val address: String? = null,
    public val updatedAt: String? = null,
    public val customClaims: Map<String, String> = emptyMap(),
) {
    /**
     * Serializes these attributes to their wire JSON object.
     *
     * `tokenIdentifier` is always written; the optional claims are omitted when
     * `null`; [customClaims] are flattened as their parsed JSON values, matching
     * the upstream serializer.
     *
     * @return the JSON object.
     * @throws ConvexJsonException when a custom claim value is not valid JSON.
     */
    public fun toJson(): JsonObject = buildJsonObject {
        put("tokenIdentifier", JsonPrimitive(tokenIdentifier.value))
        putOptionalString("issuer", issuer)
        putOptionalString("subject", subject)
        putOptionalString("name", name)
        putOptionalString("givenName", givenName)
        putOptionalString("familyName", familyName)
        putOptionalString("nickname", nickname)
        putOptionalString("preferredUsername", preferredUsername)
        putOptionalString("profileUrl", profileUrl)
        putOptionalString("pictureUrl", pictureUrl)
        putOptionalString("websiteUrl", websiteUrl)
        putOptionalString("email", email)
        putOptionalBoolean("emailVerified", emailVerified)
        putOptionalString("gender", gender)
        putOptionalString("birthday", birthday)
        putOptionalString("timezone", timezone)
        putOptionalString("language", language)
        putOptionalString("phoneNumber", phoneNumber)
        putOptionalBoolean("phoneNumberVerified", phoneNumberVerified)
        putOptionalString("address", address)
        putOptionalString("updatedAt", updatedAt)
        for ((key, value) in customClaims) {
            put(key, parseCustomClaim(key, value))
        }
    }

    /** Parsing. */
    public companion object {
        /**
         * Parses wire JSON into attributes.
         *
         * Prefers an explicit `tokenIdentifier`; otherwise composes one from
         * `issuer` and `subject`. Any key not recognised becomes a custom claim.
         *
         * @param element the JSON value, expected to be an object.
         * @return the parsed attributes.
         * @throws ConvexJsonException when [element] is not an object, a known
         *   field has the wrong type, or neither `tokenIdentifier` nor the
         *   `issuer`/`subject` pair is present.
         */
        public fun fromJson(element: JsonElement): UserIdentityAttributes {
            val json = element as? JsonObject
                ?: throw ConvexJsonException("user identity attributes must be a JSON object")
            return UserIdentityAttributes(
                tokenIdentifier = json.optionalStringField("tokenIdentifier")?.let(::UserIdentifier)
                    ?: deriveTokenIdentifier(json),
                issuer = json.optionalStringField("issuer"),
                subject = json.optionalStringField("subject"),
                name = json.optionalStringField("name"),
                givenName = json.optionalStringField("givenName"),
                familyName = json.optionalStringField("familyName"),
                nickname = json.optionalStringField("nickname"),
                preferredUsername = json.optionalStringField("preferredUsername"),
                profileUrl = json.optionalStringField("profileUrl"),
                pictureUrl = json.optionalStringField("pictureUrl"),
                websiteUrl = json.optionalStringField("websiteUrl"),
                email = json.optionalStringField("email"),
                emailVerified = json.optionalBooleanField("emailVerified"),
                gender = json.optionalStringField("gender"),
                birthday = json.optionalStringField("birthday"),
                timezone = json.optionalStringField("timezone"),
                language = json.optionalStringField("language"),
                phoneNumber = json.optionalStringField("phoneNumber"),
                phoneNumberVerified = json.optionalBooleanField("phoneNumberVerified"),
                address = json.optionalStringField("address"),
                updatedAt = json.optionalStringField("updatedAt"),
                customClaims = json.customClaims(),
            )
        }
    }
}

/** The wire names of the known attributes; everything else is a custom claim. */
private val KNOWN_IDENTITY_KEYS: Set<String> = setOf(
    "tokenIdentifier",
    "issuer",
    "subject",
    "name",
    "givenName",
    "familyName",
    "nickname",
    "preferredUsername",
    "profileUrl",
    "pictureUrl",
    "websiteUrl",
    "email",
    "emailVerified",
    "gender",
    "birthday",
    "timezone",
    "language",
    "phoneNumber",
    "phoneNumberVerified",
    "address",
    "updatedAt",
)

/**
 * Composes the identifier from `issuer` and `subject`, or fails.
 *
 * Upstream requires both when `tokenIdentifier` is absent, and names the exact
 * message the parity test checks.
 */
private fun deriveTokenIdentifier(json: JsonObject): UserIdentifier {
    val issuer = json.optionalStringField("issuer")
    val subject = json.optionalStringField("subject")
    if (issuer != null && subject != null) {
        return UserIdentifier.construct(issuer, subject)
    }
    throw ConvexJsonException("Either \"tokenIdentifier\" or \"issuer\" and \"subject\" must be set")
}

/** Reads an optional string, treating absent and `null` alike. */
private fun JsonObject.optionalStringField(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw ConvexJsonException("'$key' must be a string")
    }
    return primitive.content
}

/** Reads an optional boolean. */
private fun JsonObject.optionalBooleanField(key: String): Boolean? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.booleanOrNull
        ?: throw ConvexJsonException("'$key' must be a boolean")
}

/** Collects every unrecognised key as a custom claim, keeping raw JSON text. */
private fun JsonObject.customClaims(): Map<String, String> =
    entries.filter { it.key !in KNOWN_IDENTITY_KEYS }.associate { it.key to it.value.toString() }

/** Parses a stored custom-claim value back into JSON. */
private fun parseCustomClaim(key: String, value: String): JsonElement = try {
    Json.parseToJsonElement(value)
} catch (failure: SerializationException) {
    throw ConvexJsonException("custom claim '$key' is not valid JSON", failure)
}

/** Writes an optional string field, omitting it when `null`. */
private fun JsonObjectBuilder.putOptionalString(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

/** Writes an optional boolean field, omitting it when `null`. */
private fun JsonObjectBuilder.putOptionalBoolean(key: String, value: Boolean?) {
    if (value != null) put(key, JsonPrimitive(value))
}
