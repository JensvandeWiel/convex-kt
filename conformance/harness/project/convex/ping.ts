// Minimal Convex module used only to generate a client deployment for the
// conformance recordings. It is pushed to the pinned local backend by
// record-handshake.mjs; it is not part of the shipped Kotlin client.

import { query } from "./_generated/server";

// The handshake scenario only needs the deployment to exist, but registering a
// trivial function keeps the generated api surface non-empty and proves the
// deployment pushed cleanly.
export const ping = query({
  args: {},
  handler: async () => "pong",
});
