---
name: Check Parity
description: Validate and update convex-kt parity.yaml so CI keeps failing on drift between upstream convex-rs tests and Kotlin counterparts.
---

# Check Parity

Use this whenever you add, port, or retire a test, or when CI's parity job
fails. This is the tooling behind the "Full Test-Suite Parity" hard rule in
`AGENTS.md`.

## Run it

```bash
./gradlew :tools:parity:run --args="--manifest parity.yaml"
```

Exit codes: `0` valid, `1` invalid, `2` usage error. The task fails the build on
a non-zero exit, so CI is gated on this.

## Manifest schema

`parity.yaml` has `schemaVersion: 1` and a `requirements` list. Each entry:

| Field | Required | Notes |
| --- | --- | --- |
| `id` | yes | unique, non-empty; stable across refactors |
| `description` | recommended | one sentence; required for `not-applicable` |
| `status` | yes | `planned`, `ported`, or `not-applicable` |
| `upstream` | per status | references into `get-convex/convex-rs` |
| `kotlin` | per status | references into this repository |

Status rules enforced by `:tools:parity`:

- `planned` — needs an `upstream` reference; `kotlin` may be empty.
- `ported` — needs **both** `upstream` and `kotlin` references.
- `not-applicable` — allowed to be empty on both sides, but should explain why.

## Adding an entry

1. Get the exact upstream test name and its file/crate.
2. Add the requirement. Prefer a stable `id` such as `sync/handshake`.
3. Set `status: planned` while porting, then flip to `ported` **in the same
   change** that lands the Kotlin test.
4. Run the checker and paste its summary into the PR.

## When CI fails

- `manifest not found` — the path passed to `--manifest` is wrong.
- `unknown status` — typo; use one of the three wire names.
- `duplicate id` — two entries share an id; merge or rename.
- `need a 'kotlin' reference` — a `ported` entry is missing its Kotlin test.
- `'requirements' is empty` — warning only, not a failure; the gate activates
  once the first entry exists.

## Rules

- Do not delete a parity entry to make CI pass. Downgrade it to `planned` with a
  reason, or to `not-applicable` with a description.
- Do not invent upstream references. If you cannot find the upstream test, load
  the `port-from-rust` skill and search properly first.
