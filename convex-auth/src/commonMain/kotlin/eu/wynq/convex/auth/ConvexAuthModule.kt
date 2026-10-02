package eu.wynq.convex.auth

/**
 * Compile-time marker for the `convex-auth` module.
 *
 * `convex-auth` will implement the token lifecycle and JWT verification
 * (RS256 + ES256) behind an `expect`/`actual` crypto boundary, because the
 * signing primitives differ per platform (plan step 6). Until then this marker
 * keeps the module publishable.
 */
public object ConvexAuthModule
