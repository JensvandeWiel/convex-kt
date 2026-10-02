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

/**
 * Verifies a signature over [data] with [key].
 *
 * A platform primitive because the JVM and Android can use `java.security`
 * while Apple targets need a different backend. Implementations return `false`
 * for a mismatched signature or an unusable key; they should throw only when
 * the platform cannot perform verification at all.
 *
 * @param algorithm the JWT algorithm, which fixes the key type and digest.
 * @param key the public key, as a JWK.
 * @param data the signing input (`header.payload`).
 * @param signature the decoded signature bytes.
 * @return whether the signature is valid.
 */
internal expect fun verifySignature(
    algorithm: JwtAlgorithm,
    key: JsonWebKey,
    data: ByteArray,
    signature: ByteArray,
): Boolean
