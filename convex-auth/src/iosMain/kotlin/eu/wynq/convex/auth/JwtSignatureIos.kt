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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256

/**
 * Apple's implementation of the signature primitive, backed by the
 * Security.framework (`SecKeyVerifySignature`).
 *
 * The framework's `RSASignatureMessagePKCS1v15SHA256` algorithm hashes the
 * message itself, and `ECDSASignatureMessageX962SHA256` expects the JOSE
 * signature's DER encoding — both match what `JwtVerifier` feeds in, so no
 * digest is computed here. JVM and Android share the same behavior through
 * `java.security`; this actual exists because neither `java.security` nor a
 * JVM is present on Apple targets.
 *
 * This is deliberately dependency-free: Security.framework is part of the
 * platform, whereas a third-party crypto library would be another version to
 * pin and audit against the JVM implementation.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun verifySignature(
    algorithm: JwtAlgorithm,
    key: JsonWebKey,
    data: ByteArray,
    signature: ByteArray,
): Boolean {
    val publicKey = createPublicKey(algorithm, key) ?: return false
    try {
        return verifyWithKey(publicKey, algorithm, data, signature)
    } finally {
        CFRelease(publicKey)
    }
}

/**
 * Verifies [data] against [signature] with an existing `SecKey`.
 *
 * Split from [verifySignature] so each function stays within the single-digit
 * return budget: the caller owns the key lifetime, this owns the two `CFData`
 * lifetimes.
 */
@OptIn(ExperimentalForeignApi::class)
private fun verifyWithKey(
    publicKey: SecKeyRef,
    algorithm: JwtAlgorithm,
    data: ByteArray,
    signature: ByteArray,
): Boolean {
    val message = cfDataOf(data) ?: return false
    try {
        val frameworkSignature = toFrameworkSignature(algorithm, signature) ?: return false
        val encoded = cfDataOf(frameworkSignature) ?: return false
        try {
            return SecKeyVerifySignature(
                key = publicKey,
                algorithm = frameworkAlgorithmFor(algorithm),
                signedData = message,
                signature = encoded,
                error = null,
            )
        } finally {
            CFRelease(encoded)
        }
    } finally {
        CFRelease(message)
    }
}

/** Maps a JWT algorithm to the Security.framework algorithm that matches it. */
@OptIn(ExperimentalForeignApi::class)
private fun frameworkAlgorithmFor(algorithm: JwtAlgorithm): CFStringRef? = when (algorithm) {
    JwtAlgorithm.RS256 -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256
    JwtAlgorithm.ES256 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA256
}

/**
 * Builds a `SecKey` from the JWK.
 *
 * RSA keys use the PKCS#1 `RSAPublicKey` DER because Security.framework has no
 * API that takes a modulus and exponent directly. EC keys use the X9.63
 * uncompressed point (`0x04 || x || y`) that `SecKeyCreateWithData` expects for
 * a named curve.
 */
@OptIn(ExperimentalForeignApi::class)
private fun createPublicKey(algorithm: JwtAlgorithm, key: JsonWebKey): SecKeyRef? = when (algorithm) {
    JwtAlgorithm.RS256 -> {
        val der = rsaPublicKeyDer(key) ?: return null
        createSecKey(der, kSecAttrKeyTypeRSA)
    }

    JwtAlgorithm.ES256 -> {
        val point = ecUncompressedPoint(key) ?: return null
        createSecKey(point, kSecAttrKeyTypeECSECPrimeRandom)
    }
}

/**
 * Creates a public `SecKey` from [data] and a key type, marking it public.
 *
 * The attributes dictionary is built with `CFDictionaryCreate` rather than by
 * bridging a Kotlin `Map`, because the values are CoreFoundation string
 * constants and must reach Security.framework as `CFStringRef`s. Bridging a map
 * of those raw pointers produces an `NSDictionary` whose values are not always
 * recognized, which silently yields a key that cannot verify anything.
 *
 * @param data the PKCS#1 RSA DER or the X9.63 EC point.
 * @param keyType `kSecAttrKeyTypeRSA` or `kSecAttrKeyTypeECSECPrimeRandom`.
 */
@OptIn(ExperimentalForeignApi::class)
private fun createSecKey(data: ByteArray, keyType: CFStringRef?): SecKeyRef? {
    val encoded = cfDataOf(data) ?: return null
    try {
        return memScoped {
            val keys = allocArrayOf(kSecAttrKeyType, kSecAttrKeyClass)
            val values = allocArrayOf(keyType, kSecAttrKeyClassPublic)
            val dictionary = CFDictionaryCreate(
                allocator = null,
                keys = keys.reinterpret(),
                values = values.reinterpret(),
                numValues = 2,
                keyCallBacks = null,
                valueCallBacks = null,
            ) ?: return@memScoped null
            try {
                SecKeyCreateWithData(encoded, dictionary, null)
            } finally {
                CFRelease(dictionary)
            }
        }
    } finally {
        CFRelease(encoded)
    }
}

/**
 * Converts the JOSE signature into what Security.framework expects.
 *
 * RSA signatures are already in the wire form. ES256 uses raw `r || s`, while
 * the framework wants DER `SEQUENCE { INTEGER r, INTEGER s }`, so it is
 * converted here — mirroring the JVM path, which does the same in reverse.
 *
 * @return the signature bytes, or `null` when they are not a valid ES256
 *   signature.
 */
private fun toFrameworkSignature(algorithm: JwtAlgorithm, signature: ByteArray): ByteArray? = when (algorithm) {
    JwtAlgorithm.RS256 -> signature
    JwtAlgorithm.ES256 -> joseToDer(signature)
}

@OptIn(ExperimentalForeignApi::class)
private fun cfDataOf(bytes: ByteArray): CFDataRef? =
    bytes.usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong())
    }

private fun rsaPublicKeyDer(key: JsonWebKey): ByteArray? {
    val modulus = decodeBase64Url(key.modulus) ?: return null
    val exponent = decodeBase64Url(key.exponent) ?: return null
    val body = derInteger(modulus) + derInteger(exponent)
    return derSequence(body)
}

private fun ecUncompressedPoint(key: JsonWebKey): ByteArray? {
    val x = decodeBase64Url(key.x) ?: return null
    val y = decodeBase64Url(key.y) ?: return null
    if (x.size != ES256_COORDINATE_BYTES || y.size != ES256_COORDINATE_BYTES) return null
    return byteArrayOf(EC_UNCOMPRESSED_TAG) + x + y
}

private const val EC_UNCOMPRESSED_TAG: Byte = 0x04
