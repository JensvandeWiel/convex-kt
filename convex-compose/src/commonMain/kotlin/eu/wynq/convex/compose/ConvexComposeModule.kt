package eu.wynq.convex.compose

/**
 * Compile-time marker for the `convex-compose` module.
 *
 * `convex-compose` will expose `QueryState<T>` controllers and `@Composable`
 * bindings on top of `convex-client` (plan step 8). Until then this marker
 * keeps the module publishable and proves the Compose toolchain is wired up.
 */
public object ConvexComposeModule
