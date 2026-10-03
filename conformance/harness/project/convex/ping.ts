// Minimal Convex module used only to generate a client deployment for the
// conformance recordings. It is pushed to the pinned local backend by
// record-handshake.mjs; it is not part of the shipped Kotlin client.

import { query } from "./_generated/server";
import { v } from "convex/values";

// The handshake scenario only needs the deployment to exist, but registering a
// trivial function keeps the generated api surface non-empty and proves the
// deployment pushed cleanly.
//
// The return validator is declared so the apiSpec carries a typed result for
// convex-codegen; the functions in this project are the source of truth for the
// typed-call fixture.
export const ping = query({
  args: {},
  returns: v.string(),
  handler: async () => "pong",
});
