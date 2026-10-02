---
name: Check Parity
description: Validate and update convex-kt parity.yaml so the build fails whenever an upstream convex-rs test is not accounted for.
---

# Check Parity

Use this whenever you add, port, or retire a test, or when the parity gate
fails. This is the tooling behind the "Full Test-Suite Parity" hard rule in
`AGENTS.md`.

## What is actually enforced

`ParityCoverageTest` runs as part of `./gradlew check` (and therefore `build`).
It scans the `third_party/convex-rs` submodule for Rust test functions and fails
if any of them is missing from `parity.yaml`. Adding an upstream test without a
manifest entry breaks the build — there is no separate tool to remember.

`:tools:parity` is the same code exposed as a CLI, useful for seeing the full
report or generating entries.

## Run it

```bash
# structural validation only
./gradlew :tools:parity:run --args="--manifest parity.yaml"

# full coverage: every upstream test accounted for
./gradlew :tools:parity:run --args="--upstream third_party/convex-rs"

# absorb a new upstream revision
./gradlew :tools:parity:run --args="--emit-missing third_party/convex-rs"
```

Exit codes: `0` valid, `1` invalid, `2` usage error.

## Manifest schema

`parity.yaml` has `schemaVersion: 1` and a `requirements` list. Each entry:

| Field | Required | Notes |
| --- | --- | --- |
| `id` | yes | unique, non-empty; stable across refactors |
| `description` | recommended | one sentence; required for `not-applicable` |
| `status` | yes | `planned`, `ported`, or `not-applicable` |
| `upstream` | per status | `"<path/relative/to/convex-rs>::<fn>"` |
| `kotlin` | per status | references into this repository |

Status rules enforced by the tool:

- `planned` — needs an `upstream` reference; `kotlin` may be empty.
- `ported` — needs **both** `upstream` and `kotlin` references.
- `not-applicable` — may be empty on both sides, but should explain why.

## Adding an entry

1. Prefer generation over typing:
   ```bash
   ./gradlew :tools:parity:run --args="--emit-missing third_party/convex-rs"
   ```
   Append the output to `parity.yaml`. Each emitted reference is guaranteed to
   match a real upstream test, because discovery produced it.
2. Set `status: planned` while porting, then flip to `ported` **in the same
   change** that lands the Kotlin test.
3. Run `./gradlew :tools:parity:run --args="--upstream third_party/convex-rs"`.

## Reading a failure

- `missing: <ref>` — an upstream test has no manifest entry. Generate one.
- `orphaned reference: <ref>` — a manifest entry names a test that no longer
  exists upstream (renamed or removed). Fix or remove the entry.
- `manifest not found` / `unknown status` / `duplicate id` — structural problems.
- The test naming the submodule is missing means `git submodule update --init
  --recursive` was not run.

## Rules

- Do not delete or downgrade a parity entry to make CI pass. Mark it `planned`
  with a reason, or `not-applicable` with a description.
- Do not invent upstream references by hand; generate them.
- When the submodule pin moves, re-run `--emit-missing` and review every new
  entry. The pin is a deliberate, reviewed change.
