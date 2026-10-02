package eu.wynq.convex.codegen

/**
 * Compile-time marker for the `convex-codegen` module.
 *
 * `convex-codegen` will map a Convex `apiSpec` JSON document to typed Kotlin
 * `ConvexFunction` descriptors at build time (plan step 9). Until then this
 * marker keeps the JVM module publishable.
 */
public object ConvexCodegenModule
