package eu.wynq.convex.core

/**
 * Compile-time marker for the `convex-core` module.
 *
 * `convex-core` will own the pure Kotlin value types, the JSON codecs that keep
 * 64-bit integer precision intact, the protocol message types, and the sync
 * state machine (plan step 4). This marker exists so the scaffold builds and
 * publishes an artifact until that work lands, and so `explicitApi()` has a
 * declaration to verify.
 */
public object ConvexCoreModule
