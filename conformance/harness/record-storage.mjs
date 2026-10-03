// Conformance recorder: `storage` scenario.
//
// Storage URLs are generated inside Convex functions, so this script calls the
// conformance project's `storage:generateUploadUrl` mutation and
// `storage:getFileUrl` query over the HTTP functions API, uploads a known
// payload, downloads it back, and records the response shapes.
//
// Usage (from conformance/harness):
//   node record-storage.mjs --url http://127.0.0.1:3210 --admin-key <key>

import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "../..");
const PROJECT = resolve(HERE, "project");
const FIXTURE = resolve(REPO, "conformance/fixtures/storage");

const PAYLOAD = new TextEncoder().encode("convex-kt storage fixture");

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

async function callFunction(base, kind, path, args) {
  const response = await fetch(`${base}/api/${kind}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path, args, format: "json" }),
  });
  const body = await response.json();
  if (body.status !== "success") {
    throw new Error(`${kind} ${path} failed: ${JSON.stringify(body)}`);
  }
  return body.value;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  console.log(`backend: ${args.url}`);
  generateDeployment(args);

  const uploadUrl = await callFunction(args.url, "mutation", "storage:generateUploadUrl", {});

  const uploadResponse = await fetch(uploadUrl, {
    method: "POST",
    headers: { "Content-Type": "application/octet-stream" },
    body: PAYLOAD,
  });
  const uploadBody = await uploadResponse.text();
  const storageId = JSON.parse(uploadBody).storageId;
  console.log(`uploaded -> ${storageId}`);

  const fileUrl = await callFunction(args.url, "query", "storage:getFileUrl", { storageId });
  const downloaded = new Uint8Array(await (await fetch(fileUrl)).arrayBuffer());
  const roundTrips = Buffer.from(downloaded).equals(Buffer.from(PAYLOAD));
  console.log(`downloaded ${downloaded.length} bytes, round-trips=${roundTrips}`);
  if (!roundTrips) throw new Error("downloaded bytes do not match the uploaded payload");

  mkdirSync(FIXTURE, { recursive: true });
  writeFileSync(
    resolve(FIXTURE, "exchange.json"),
    `${JSON.stringify(
      {
        // Response shapes only; the signed URLs and the storage id vary per run.
        uploadResponseBody: { storageId: "<STORAGE_ID>" },
        payloadUtf8: Buffer.from(PAYLOAD).toString("utf8"),
        fileUrlIsAbsolute: /^https?:\/\//.test(fileUrl),
        downloadedBytes: downloaded.length,
      },
      null,
      2,
    )}\n`,
  );
  console.log(`wrote ${resolve(FIXTURE, "exchange.json")}`);
}

main().catch((error) => {
  console.error(error.stack ?? error.message);
  process.exit(1);
});
