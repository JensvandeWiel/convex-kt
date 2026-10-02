package eu.wynq.convex.client

/**
 * Compile-time marker for the `convex-client` module.
 *
 * `convex-client` will implement the `SyncProtocol` transport seam (Ktor
 * OkHttp/Darwin) and drive the core state machine against the recorded
 * conformance fixtures (plan step 5). Until then this marker keeps the module
 * publishable.
 */
public object ConvexClientModule
