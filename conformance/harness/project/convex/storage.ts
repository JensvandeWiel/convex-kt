import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

// Storage URLs are minted inside functions, not by a client HTTP endpoint, so
// the conformance recording calls these two to obtain a pre-signed upload URL
// and a signed download URL.

export const generateUploadUrl = mutation({
  args: {},
  returns: v.string(),
  handler: async (ctx) => {
    return await ctx.storage.generateUploadUrl();
  },
});

export const getFileUrl = query({
  args: { storageId: v.id("_storage") },
  // A missing storage object legitimately returns null.
  returns: v.union(v.string(), v.null()),
  handler: async (ctx, { storageId }) => {
    return await ctx.storage.getUrl(storageId);
  },
});
