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

/**
 * Decodes an un-padded base64url string, or returns `null` when it is malformed.
 *
 * JOSE (RFC 7515 §2) mandates base64url without `=` padding, but Kotlin's
 * [Base64.UrlSafe] requires canonical padding and throws on the JOSE form. Every
 * header, payload, signature, and JWK coordinate in a real token is therefore
 * unpadded, so this pads before decoding. Centralizing it means the JWT body and
 * the JWK fields cannot disagree about how the same encoding works.
 *
 * @param text the base64url text, with or without padding.
 * @return the decoded bytes, or `null` when [text] is not valid base64url.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeBase64UrlOrNull(text: String): ByteArray? =
    try {
        Base64.UrlSafe.decode(padBase64Url(text))
    } catch (expected: IllegalArgumentException) {
        null
    }

/**
 * Restores the `=` padding JOSE omits, so [Base64.UrlSafe] accepts the input.
 *
 * A base64url string may already be padded; only the remainder is used to decide
 * how much to add, so passing padded input is harmless.
 *
 * @param text the base64url text.
 * @return the same text with canonical padding.
 */
internal fun padBase64Url(text: String): String {
    val remainder = text.length % BASE64_QUANTUM
    return if (remainder == 0) text else text + "=".repeat(BASE64_QUANTUM - remainder)
}

/** `=` padding restores a base64 string to a multiple of four characters. */
private const val BASE64_QUANTUM = 4
