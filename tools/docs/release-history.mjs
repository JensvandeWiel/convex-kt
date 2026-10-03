// Generates the Writerside "Version history" topic from this repository's
// GitHub releases. The Docs workflow runs it before building the guide, so the
// published page always lists the newest releases. The committed
// Writerside/topics/release-notes.md is the fallback used by local builds.
//
// Usage: node tools/docs/release-history.mjs <owner/repo> > Writerside/topics/release-notes.md

const repo = process.argv[2] ?? process.env.GITHUB_REPOSITORY;
if (!repo?.includes("/")) {
  console.error("usage: release-history.mjs <owner/repo>");
  process.exit(2);
}

const token = process.env.GH_TOKEN ?? process.env.GITHUB_TOKEN;

async function fetchReleases() {
  const response = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=100`, {
    headers: {
      accept: "application/vnd.github+json",
      "user-agent": "convex-kt-docs",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  const releases = await response.json();
  if (!Array.isArray(releases)) {
    throw new Error("unexpected response body");
  }
  return releases;
}

let releases;
try {
  releases = await fetchReleases();
} catch (error) {
  // A transient API failure must not block a docs release; fall back to the
  // static page and regenerate on the next publish.
  console.error(`warning: could not read GitHub releases (${error.message}); using the fallback page`);
  releases = [];
}

process.stdout.write(render(repo, releases));

function render(repository, items) {
  const lines = [
    "# Version history",
    "",
    "Newest first. Each version links to its full release notes on GitHub.",
    "",
  ];
  if (items.length === 0) {
    lines.push("No releases yet.", "");
  } else {
    lines.push("| Version | Released | Summary |", "| --- | --- | --- |");
    for (const release of items) {
      const version = release.name || release.tag_name;
      const released = release.published_at ? release.published_at.slice(0, 10) : "";
      lines.push(`| [${escapeTable(version)}](${release.html_url}) | ${released} | ${summary(release.body)} |`);
    }
    lines.push("");
  }
  lines.push(`Watch [GitHub releases](https://github.com/${repository}/releases) for updates.`, "");
  return lines.join("\n");
}

function summary(body) {
  const first = (body ?? "")
    .split("\n")
    .map((line) => line.trim())
    .find((line) => line.length > 0) ?? "";
  const plain = first.replace(/^#+\s*/, "").replace(/^[-*]\s*/, "");
  const clipped = plain.length > 120 ? `${plain.slice(0, 117)}...` : plain;
  return escapeTable(clipped);
}

function escapeTable(text) {
  return text.replaceAll("|", "\\|");
}