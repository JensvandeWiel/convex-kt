package eu.wynq.convex.storage

/**
 * Compile-time marker for the `convex-storage` module.
 *
 * `convex-storage` will implement file upload, download, and URL generation —
 * the only module in the project that performs plain HTTP requests (plan step
 * 7). Until then this marker keeps the module publishable.
 */
public object ConvexStorageModule
