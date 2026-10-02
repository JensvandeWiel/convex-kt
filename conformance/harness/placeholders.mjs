// Recorded-data placeholder tokens.
//
// A recording is only reproducible if every run-varying value is replaced by a
// stable token before it is committed. This module owns that vocabulary so the
// recorder and any reader agree on the exact spelling.
//
// IMPORTANT: the reader's real-world values arrive as JSON *numbers* that exceed
// 2^53 (Convex representable timestamps are 64-bit). They are substituted in the
// raw frame text, before any JSON parsing, so no precision is lost.

export const PLACEHOLDERS = {
  /** Token value issued by Fivetran on every connection. */
  token: "<TOKEN>",
  /** Session id assigned by the server during Connect. */
  sessionId: "<SESSION_ID>",
  /** Client-generated resume id, absent in this handshake scenario. */
  resumeId: "<RESUME_ID>",
  /** Unsigned 64-bit "now" timestamp in the Connect response. */
  timestamp: "<TIMESTAMP_U64>",
};

/**
 * Builds the ordered list of textual substitutions for one recording.
 *
 * Order matters: longer and more specific values must be replaced before
 * shorter ones so a substring cannot partially clobber another replacement.
 *
 * @param {{token?: string, sessionId?: string, resumeId?: string, timestampU64?: string}} observed
 *   concrete values observed during the run; each becomes a placeholder.
 * @returns {Array<{value: string, token: string}>} substitutions, longest value first.
 */
export function substitutions(observed) {
  const pairs = [
    { value: observed.sessionId, token: PLACEHOLDERS.sessionId },
    { value: observed.resumeId, token: PLACEHOLDERS.resumeId },
    { value: observed.timestampU64, token: PLACEHOLDERS.timestamp },
    { value: observed.token, token: PLACEHOLDERS.token },
  ].filter((pair) => typeof pair.value === "string" && pair.value.length > 0);

  return pairs.sort((a, b) => b.value.length - a.value.length);
}

/**
 * Normalizes one raw frame by replacing observed values with placeholders.
 *
 * @param {string} raw the exact frame text as sent or received.
 * @param {Array<{value: string, token: string}>} subs substitutions to apply.
 * @returns {string} the normalized frame text.
 */
export function normalize(raw, subs) {
  let text = raw;
  for (const { value, token } of subs) {
    text = text.split(value).join(token);
  }
  return text;
}
