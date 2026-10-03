# Auth

`convex-auth` parses JWTs and verifies RS256/ES256 signatures locally on
every target — `java.security` on JVM and Android, Apple
Security.framework on iOS. There is deliberately no third-party crypto
dependency: the platform verifies, this module shapes keys and signatures
for it.

## Verifying a token

```kotlin
import eu.wynq.convex.auth.JsonWebKey
import eu.wynq.convex.auth.JwtVerificationResult
import eu.wynq.convex.auth.JwtVerifier

when (val result = JwtVerifier.verify(token, keys, nowEpochSeconds)) {
    is JwtVerificationResult.Valid -> handle(result.token.claims)
    is JwtVerificationResult.Invalid -> reject(result.reason)
}
```

- `keys` are JWKs (`JsonWebKey.parse(element)`); selection is by `kid`
  when the header carries one, otherwise every key of the matching `kty`
  (and declared `alg`) is tried.
- `exp` is enforced against `nowEpochSeconds`; expiry, unsupported `alg`,
  key mismatch, and malformed structure all surface as `Invalid(reason)`,
  never as an exception. Only structurally unusable input to `Jwt.parse`
  throws `ConvexJwtException`.
- Parsing accepts the JOSE base64url form throughout (unpadded, no `=`
  padding), for headers, payloads, signatures, and JWK coordinates alike.

`Jwt` also exposes `header` (`JwtHeader` with resolved `JwtAlgorithm`,
`keyId`, `type`), `claims` (`issuer`, `subject`, `audience`, `exp`/`iat`/
`nbf`, plus every scalar claim in `raw`), and `isExpired(now)`.

## Authenticating the sync client

Verification tells you a token is genuine; authentication tells the
*backend* who you are. Supply tokens through `AuthTokenFetcher`:

```kotlin
import eu.wynq.convex.client.AuthTokenFetcher
import eu.wynq.convex.core.protocol.AuthenticationToken

val client = ConvexSyncClient(
    factory = ...,
    scope = ...,
    authFetcher = AuthTokenFetcher { forceRefresh ->
        val jwt = if (forceRefresh) session.refreshToken() else session.currentToken()
        if (jwt == null) AuthenticationToken.None else AuthenticationToken.User(jwt)
    },
)
```

Token kinds are `AuthenticationToken.Admin(value, actingAs?)`,
`AuthenticationToken.User(jwt)`, and `AuthenticationToken.None` (skip the
`Authenticate` message, e.g. logged-out state). The fetcher runs on
connect and with `forceRefresh = true` on every reconnect, so rotation is
just returning the fresh token when asked. Rejected or expired tokens
arrive on `client.authErrors` without dropping subscriptions — see
[Sync client](sync-client.md).

## Platform notes

- RSA uses PKCS#1 DER (`RSAPublicKey`) because Security.framework takes no
  bare modulus/exponent; EC uses the X9.63 uncompressed point. ES256 JOSE
  `r‖s` is converted to DER `SEQUENCE { INTEGER r, INTEGER s }` on every
  platform, JVM included.
- The `verifySignature` primitive is `internal expect/actual` per platform;
  only `JwtVerifier` and the parsing types are public API.
