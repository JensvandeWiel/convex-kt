// Conformance recorder: `connect-handshake` scenario.
//
// Boots against the pinned local backend, generates a valid client deployment
// with `convex deploy`, drives it with the JavaScript Convex client, and records
// every WebSocket frame in both directions into fixtures.
//
// The recording is normalized before writing so repeated runs are
// byte-identical except for values documented in the scenario README.
//
// Usage (from conformance/harness):
//   node record-handshake.mjs --url http://127.0.0.1:3210 --admin-key <key>

import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { ConvexClient } from "convex/browser";
import WebSocket from "ws";
import { normalize, substitutions } from "./placeholders.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "../..");
const PROJECT = resolve(HERE, "project");
const FIXTURE = resolve(REPO, "conformance/fixtures/connect-handshake");

const SUITE_HEADER = "convex-kt/conformance-fixture/1";
const CLIENT_VERSION = "convex-kt-conformance-harness";

function parseArgs(argv) {
  const args = { url: "http://127.0.0.1:3210", adminKey: "" };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === "--url") args.url = argv[++i];
    else if (argv[i] === "--admin-key") args.adminKey = argv[++i];
    else throw new Error(`unknown argument: ${argv[i]}`);
  }
  if (!args.adminKey) {
    throw new Error("--admin-key is required (docker compose exec backend ./generate_admin_key.sh)");
  }
  return args;
}

function generateDeployment({ url, adminKey }) {
  // Self-hosted targeting is selected with environment variables, not
  // positional arguments (the CLI's deploy command takes none). See the
  // backend's self-hosted README.
  // npm ships `npx.cmd` only on Windows; POSIX uses `npx`.
  const npx = process.platform === "win32" ? "npx.cmd" : "npx";
  const result = spawnSync(npx, ["--no-install", "convex", "deploy", "-y"], {
    cwd: PROJECT,
    encoding: "utf8",
    env: {
      ...process.env,
      CONVEX_SELF_HOSTED_URL: url,
      CONVEX_SELF_HOSTED_ADMIN_KEY: adminKey,
    },
    shell: true,
  });
  if (result.status !== 0) {
    throw new Error(`convex deploy failed (${result.status}):\n${result.stdout}\n${result.stderr}`);
  }
  return result.stdout;
}

function baseClientOptions(url) {
  return {
    url,
    webSocketConstructor: WebSocket,
    skipConvexDeploymentUrlCheck: true,
    logger: false,
  };
}

function recordFrame(frames, direction, raw) {
  frames.push({ direction, receivedAtMs: Date.now(), data: raw });
}

/**
 * Opens a WebSocket and records both directions.
 *
 * [drive] receives `send` and the recorder's `finish`. It must call `finish`
 * exactly once when the scenario is complete; the returned promise resolves
 * with every frame recorded up to that point.
 *
 * @param {string} wsUrl
 * @param {(send: (text: string) => void, finish: () => void) => void} drive
 * @returns {Promise<{frames: Array<{direction: string, receivedAtMs: number, data: string}>, failure: Error | null}>}
 */
function capture(wsUrl, drive) {
  return new Promise((resolveCapture) => {
    const frames = [];
    const socket = new WebSocket(wsUrl);
    let settled = false;
    let finishTimer = null;

    const finish = () => {
      if (settled) return;
      settled = true;
      if (finishTimer) clearTimeout(finishTimer);
      try {
        socket.close();
      } catch {
        /* already closed */
      }
      resolveCapture({ frames, failure: null });
    };

    const fail = (error) => {
      if (settled) return;
      settled = true;
      if (finishTimer) clearTimeout(finishTimer);
      try {
        socket.close();
      } catch {
        /* already closed */
      }
      resolveCapture({ frames, failure: error });
    };

    socket.on("message", (data) => {
      recordFrame(frames, "server-to-client", String(data));
      // Close once the server has replied, after a short drain delay so any
      // trailing frame in the same tick is still captured.
      if (!settled && finishTimer === null) {
        finishTimer = setTimeout(finish, 50);
      }
    });
    socket.on("error", fail);
    socket.on("unexpected-response", (_request, response) =>
      fail(new Error(`unexpected response: HTTP ${response.statusCode}`)),
    );
    socket.on("close", () => {
      // The server or a socket error ended the session before the scenario
      // called finish; resolve whatever was recorded rather than hanging.
      if (!settled) {
        settled = true;
        if (finishTimer) clearTimeout(finishTimer);
        resolveCapture({ frames, failure: null });
      }
    });
    socket.on("open", () => {
      drive((text) => {
        recordFrame(frames, "client-to-server", text);
        socket.send(text);
      }, finish);
    });
  });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  console.log(`backend:   ${args.url}`);

  console.log("generating a client deployment...");
  generateDeployment(args);

  // `convex deploy` in self-hosted mode does not write a .env.local. The
  // deployment URL is `client.url` (already an HTTP URL) on the underlying
  // client; constructing the client also proves the admin key is accepted.
  const adminClient = new ConvexClient(args.url, baseClientOptions(args.url));
  adminClient.setAdminAuth(args.adminKey);
  const convexUrl = adminClient.client.url;
  if (!convexUrl) {
    throw new Error("could not resolve the client deployment URL");
  }
  console.log(`deployment: ${convexUrl}`);
  const wsUrl = `${convexUrl.replace(/^http/, "ws")}/api/sync`;
  console.log(`recording:  ${wsUrl}`);

  // --- Scenario: connect, await Connected, close ----------------------------
  // Wire fields are camelCase (the Rust `ClientMessage::Connect` snake_case
  // fields are renamed by its custom serializer). `session_id` must be a UUID;
  // a bare token fails with "invalid length: found 6".
  const sessionId = randomUUID();
  const handshake = await capture(wsUrl, (send) => {
    send(
      JSON.stringify({
        type: "Connect",
        sessionId,
        connectionCount: 0,
        lastCloseReason: "InitialConnect",
        maxObservedTimestamp: null,
        clientTs: null,
      }),
    );
    // `capture` finishes on its own once the server replies.
  });

  if (handshake.failure) throw handshake.failure;
  if (!handshake.frames.some((f) => f.direction === "server-to-client")) {
    throw new Error("no server frame was recorded; the handshake did not complete");
  }

  // Only values observed in this scenario are substituted. `placeholders.mjs`
  // also defines a 64-bit timestamp token for scenarios that carry one, but a
  // fresh handshake answers with Ping, so there is none to replace here.
  const subs = substitutions({
    token: args.adminKey,
    sessionId,
  });

  mkdirSync(FIXTURE, { recursive: true });
  const header = (direction) => ({ suite: SUITE_HEADER, direction, clientVersion: CLIENT_VERSION });
  for (const direction of ["client-to-server", "server-to-client"]) {
    const lines = handshake.frames
      .filter((frame) => frame.direction === direction)
      .map((frame) => JSON.stringify({ header: header(direction), data: normalize(frame.data, subs) }));
    writeFileSync(resolve(FIXTURE, `${direction}.ndjson`), `${lines.join("\n")}\n`);
  }

  adminClient.close();
  console.log(`wrote fixtures to ${FIXTURE}`);
  console.log(`  client frames: ${handshake.frames.filter((f) => f.direction === "client-to-server").length}`);
  console.log(`  server frames: ${handshake.frames.filter((f) => f.direction === "server-to-client").length}`);
  console.log(`  placeholders:  ${subs.map((s) => s.token).join(", ") || "(none)"}`);
}

main().catch((error) => {
  console.error(error.stack ?? error.message);
  process.exitCode = 1;
});
