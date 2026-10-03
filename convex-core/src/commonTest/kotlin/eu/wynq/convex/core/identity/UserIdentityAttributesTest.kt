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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers user-identity attribute JSON.
 *
 * Mirrors the upstream `convex-rs` `sync_types/src/types/json.rs` tests. The
 * round trip is proven upstream with proptest over arbitrary attributes; Kotlin
 * has no property runner, so the same property is asserted over a corpus that
 * spans every optional field group, an explicit and a derived identifier, and
 * custom claims of each JSON shape.
 */
class UserIdentityAttributesTest {

    @Test
    fun deserializeTokenIdentifierGiven() {
        val serialized = """{"tokenIdentifier":"fake_identifier"}"""
        val deserialized = UserIdentityAttributes.fromJson(Json.parseToJsonElement(serialized))
        assertEquals(UserIdentifier("fake_identifier"), deserialized.tokenIdentifier)
    }

    @Test
    fun deserializeTokenIdentifierDeriver() {
        val serialized = """{"issuer":"fake_issuer", "subject":"fake_subject"}"""
        val deserialized = UserIdentityAttributes.fromJson(Json.parseToJsonElement(serialized))
        assertEquals(
            UserIdentifier.construct("fake_issuer", "fake_subject"),
            deserialized.tokenIdentifier,
        )
        assertEquals("fake_issuer|fake_subject", deserialized.tokenIdentifier.value)
    }

    @Test
    fun deserializeTokenIdentifierCannotDerive() {
        val serialized = """{"issuer":"fake_issuer"}"""
        val failure = assertFailsWith<ConvexJsonException> {
            UserIdentityAttributes.fromJson(Json.parseToJsonElement(serialized))
        }
        assertTrue(
            failure.message!!.contains(
                "Either \"tokenIdentifier\" or \"issuer\" and \"subject\" must be set",
            ),
            "got: ${failure.message}",
        )
    }

    @Test
    fun proptestUserIdentityAttributesRoundtrips() {
        for (attributes in corpus) {
            val decoded = UserIdentityAttributes.fromJson(attributes.toJson())
            assertEquals(attributes, decoded, "round trip failed for $attributes")
        }
    }

    private val corpus = listOf(
        UserIdentityAttributes(UserIdentifier.construct("convex", "fake_user")),
        UserIdentityAttributes(
            tokenIdentifier = UserIdentifier("fake_identifier"),
            issuer = "issuer",
            subject = "subject",
            name = "Barbara Liskov",
            givenName = "Barbara",
            familyName = "Liskov",
            nickname = "bl",
            preferredUsername = "bliskov",
            profileUrl = "https://example.com/bl",
            pictureUrl = "https://example.com/bl.png",
            websiteUrl = "https://example.com",
            email = "bl@example.com",
            emailVerified = false,
            gender = "female",
            birthday = "1939-11-07",
            timezone = "UTC",
            language = "en",
            phoneNumber = "+15551234567",
            phoneNumberVerified = true,
            address = "1 Main St",
            updatedAt = "2026-01-01T00:00:00Z",
            customClaims = mapOf(
                "role" to "\"admin\"",
                "count" to "3",
                "nested" to """{"a":1}""",
            ),
        ),
        UserIdentityAttributes(
            tokenIdentifier = UserIdentifier("issuer|subject"),
            emailVerified = true,
            phoneNumberVerified = false,
        ),
    )
}
