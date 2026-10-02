import { action, mutation, query } from "./_generated/server";
import { v } from "convex/values";

// Minimal module for the `query-and-mutation` conformance scenario. `list` is
// subscribed to and `send` is invoked, so the recording captures a real
// ModifyQuerySet, Transition, Mutation, and MutationResponse.

export const list = query({
  args: {},
  handler: async (ctx) => {
    return await ctx.db.query("messages").collect();
  },
});

export const send = mutation({
  args: { body: v.string() },
  handler: async (ctx, { body }) => {
    await ctx.db.insert("messages", { body });
    return body.length;
  },
});

export const echo = action({
  args: { body: v.string() },
  handler: async (_ctx, { body }) => {
    return body;
  },
});

// Deliberately slow, so an integration test can observe an optimistic update
// before the server acknowledges it. Actions may await timers.
export const slowEcho = action({
  args: { body: v.string() },
  handler: async (_ctx, { body }) => {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    return body;
  },
});

// Used by the recorder to reset state before capturing, so the `list` value is
// deterministic regardless of what earlier runs left in the database.
export const clear = mutation({
  args: {},
  handler: async (ctx) => {
    const all = await ctx.db.query("messages").collect();
    for (const message of all) {
      await ctx.db.delete(message._id);
    }
  },
});
