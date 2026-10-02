#!/usr/bin/env node
// Upstream release watcher for convex-kt.
//
// Compares the pinned upstream commits/releases in .github/upstream-pins.json
// against the latest GitHub release of each repository. On drift it drafts a
// tracking issue in *this* repository. It never edits code, never opens a pull
// request, and never merges — see the "Automated Upstream Detection" rule in
// AGENTS.md.
//
// A repository with an empty `pinned` value is reported as unpinned and is not
// diffed, so setting the pin is a deliberate, reviewed step.

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PINS_PATH = resolve(HERE, "../../.github/upstream-pins.json");
const TOKEN = process.env.GH_TOKEN ?? process.env.GITHUB_TOKEN;
const OUR_REPO = process.env.GITHUB_REPOSITORY; // owner/name, set by Actions
const API = "https://api.github.com";
const LABEL = "upstream-tracking";
const TITLE_PREFIX = "[upstream]";

function requireEnv() {
  if (!TOKEN) throw new Error("GH_TOKEN (or GITHUB_TOKEN) is required");
  if (!OUR_REPO) throw new Error("GITHUB_REPOSITORY is required");
}

async function api(path, init = {}) {
  const response = await fetch(`${API}${path}`, {
    ...init,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${TOKEN}`,
      "User-Agent": "convex-kt-upstream-watch",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(init.headers ?? {}),
    },
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${init.method ?? "GET"} ${path} -> ${response.status}: ${body}`);
  }
  return response.status === 204 ? null : response.json();
}

async function ensureLabel() {
  await api(`/repos/${OUR_REPO}/labels/${LABEL}`, {
    method: "PUT",
    body: JSON.stringify({
      name: LABEL,
      color: "5319e7",
      description: "Tracks drift between pinned upstream revisions and releases",
    }),
  });
}

async function openIssues() {
  const issues = await api(
    `/repos/${OUR_REPO}/issues?state=open&labels=${LABEL}&per_page=100`,
  );
  return issues.map((issue) => issue.title);
}

async function latestRelease(repo) {
  const release = await api(`/repos/${repo}/releases/latest`);
  return { tag: release.tag_name, url: release.html_url, name: release.name ?? release.tag_name };
}

function buildBody(entry, release) {
  return [
    `The pinned revision for **${entry.name}** (\`${entry.repo}\`) has drifted.`,
    "",
    `- Pinned: \`${entry.pinned}\``,
    `- Latest release: [${release.tag}](${release.url})`,
    "",
    "## Required follow-up",
    "",
    "- [ ] Diff the protocol paths between the pinned revision and the new release.",
    "- [ ] Decide whether the client's wire behavior is affected.",
    "- [ ] If affected, open a separate change; update `.github/upstream-pins.json` and `parity.yaml`.",
    "- [ ] Re-capture conformance fixtures from the new pin if the protocol changed.",
    "",
    "_Opened automatically by `tools/upstream-watch/watch.mjs`. No code was changed._",
  ].join("\n");
}

async function main() {
  requireEnv();
  const pins = JSON.parse(readFileSync(PINS_PATH, "utf8"));
  await ensureLabel();
  const existing = new Set(await openIssues());

  let drafted = 0;
  for (const entry of pins.repositories) {
    if (!entry.pinned) {
      console.log(`skip ${entry.name}: no pin recorded yet`);
      continue;
    }
    const release = await latestRelease(entry.repo);
    if (release.tag === entry.pinned) {
      console.log(`ok   ${entry.name}: ${release.tag}`);
      continue;
    }
    const title = `${TITLE_PREFIX} ${entry.name} moved from ${entry.pinned} to ${release.tag}`;
    if (existing.has(title)) {
      console.log(`seen ${entry.name}: issue already open`);
      continue;
    }
    await api(`/repos/${OUR_REPO}/issues`, {
      method: "POST",
      body: JSON.stringify({ title, body: buildBody(entry, release), labels: [LABEL] }),
    });
    console.log(`drafted ${entry.name}: ${release.tag}`);
    drafted += 1;
  }
  console.log(`done: ${drafted} issue(s) drafted`);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
