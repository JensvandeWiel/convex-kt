// Conformance recorder: `query-and-mutation` scenario.
//
// Unlike record-handshake.mjs, this drives the real convex-js client so the
// frames are authentic reference-client traffic rather than hand-authored. It
// records both directions while the client subscribes to a query and runs a
// mutation, which produces real Transition and MutationResponse frames.
//
// Run-variant values are replaced with fixed, correctly-typed constants so the
// committed fixtures are byte-stable and still decode cleanly:
//   sessionId -> a fixed UUID
//   clientTs / serverTs / clientClockSkew -> 0
//   ts (base64) -> the base64 of zero
//   _creationTime -> 0, _id -> a fixed id, journal -> null
//
// Usage (from conformance/harness):
//   node record-subscription.mjs --url http://127.0.0.1:3210 --admin-key <key>

import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { ConvexClient, ConvexHttpClient } from "convex/browser";
import { WebSocket as WsWebSocket } from "ws";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "../..");
const PROJECT = resolve(HERE, "project");
const FIXTURE = resolve(REPO, "conformance/fixtures/query-and-mutation");

const SUITE_HEADER = "convex-kt/conformance-fixture/1";
const CLIENT_VERSION = "convex-kt-conformance-harness";

const FIXED_SESSION_ID = "00000000-0000-4000-8000-000000000000";
const FIXED_DOC_ID = "0000000000000000000000000000000000000000";

const frames = [];
let recording = false;

function record(direction, data) {
  if (!recording) return;
  frames.push({ direction, data: String(data) });
}

/** A WebSocket that records every frame before delegating to `ws`. */
class RecordingWebSocket extends WsWebSocket {
  constructor(address, protocols, options) {
    super(address, protocols, options);
    this.addEventListener("message", (event) => record("server-to-client", event.data));
  }

  send(data, ...rest) {
    record("client-to-server", data);
    return super.send(data, ...rest);
  }
}

function normalize(text) {
  return text
    .replace(/"sessionId":"[0-9a-fA-F-]{36}"/g, `"sessionId":"${FIXED_SESSION_ID}"`)
    .replace(/"clientTs":-?[0-9]+/g, '"clientTs":0')
    .replace(/"serverTs":-?[0-9]+/g, '"serverTs":0')
    .replace(/"clientClockSkew":-?[0-9]+/g, '"clientClockSkew":0')
    .replace(/"ts":"[A-Za-z0-9+/]{10,}={0,2}"/g, '"ts":"AAAAAAAAAAA="')
    .replace(/"journal":"[^"]*"/g, '"journal":null')
    .replace(/"_creationTime":-?[0-9.]+/g, '"_creationTime":0')
    .replace(/"_id":"[a-z0-9]+"/g, `"_id":"${FIXED_DOC_ID}"`);
}

function parseArgs(argv) {
  const args = { url: "http://127.0.0.1:3210", adminKey: "" };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === "--url") args.url = argv[++i];
    else if (argv[i] === "--admin-key") args.adminKey = argv[++i];
    else throw new Error(`unknown argument: ${argv[i]}`);
  }
  return args;
}

function generateDeployment({ url, adminKey }) {
  // npm ships `npx.cmd` only on Windows; POSIX uses `npx`.
  const npx = process.platform === "win32" ? "npx.cmd" : "npx";
  const result = spawnSync(npx, ["--no-install", "convex", "deploy", "-y"], {
    cwd: PROJECT,
    encoding: "utf8",
    env: { ...process.env, CONVEX_SELF_HOSTED_URL: url, CONVEX_SELF_HOSTED_ADMIN_KEY: adminKey },
    shell: true,
  });
  if (result.status !== 0) {
    throw new Error(`convex deploy failed (${result.status}):\n${result.stdout}\n${result.stderr}`);
  }
}

function delay(ms) {
  return new Promise((done) => setTimeout(done, ms));
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  console.log(`backend: ${args.url}`);

  if (args.adminKey) {
    console.log("generating a client deployment...");
    generateDeployment(args);
  }

  // Reset the collection over HTTP with a separate client, so the recording
  // client's first frame is Connect rather than the reset call.
  const http = new ConvexHttpClient(args.url);
  await http.mutation("messages:clear", {});
  await delay(200);

  const client = new ConvexClient(args.url, {
    webSocketConstructor: RecordingWebSocket,
    skipConvexDeploymentUrlCheck: true,
    logger: false,
  });

  recording = true;
  const updates = [];
  const unsubscribe = client.onUpdate("messages:list", {}, (result) => {
    updates.push(result);
  });
  await delay(1500);

  await client.mutation("messages:send", { body: "hello" });
  await delay(1500);

  unsubscribe();
  await delay(200);
  recording = false;
  client.close();

  mkdirSync(FIXTURE, { recursive: true });
  const header = (direction) => ({ suite: SUITE_HEADER, direction, clientVersion: CLIENT_VERSION });
  for (const direction of ["client-to-server", "server-to-client"]) {
    const lines = frames
      .filter((frame) => frame.direction === direction)
      .map((frame) => JSON.stringify({ header: header(direction), data: normalize(frame.data) }));
    writeFileSync(resolve(FIXTURE, `${direction}.ndjson`), `${lines.join("\n")}\n`);
  }

  console.log(`captured ${frames.length} frames (${updates.length} query updates) -> ${FIXTURE}`);
  for (const frame of frames) {
    const arrow = frame.direction === "client-to-server" ? "--> " : "<-- ";
    console.log(`${arrow}${normalize(frame.data)}`);
  }
  process.exit(0);
}

main().catch((error) => {
  console.error(error.stack ?? error.message);
  process.exit(1);
});
