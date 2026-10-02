# Contributing to convex-kt

Thanks for helping build a Kotlin Multiplatform client for Convex. This file is
the short path in; `AGENTS.md` is the authoritative working agreement and wins if
the two disagree.

## Before you start

Read, in order:

1. `AGENTS.md` — the three hard rules, development guardrails, and the
   Rust → Kotlin mapping. This is not optional reading.
2. `README.md` — module map and build commands.
3. `.opencode/skills/` — the executable workflows (`port-from-rust`,
   `capture-conformance`, `check-parity`, `new-function`).

## Setup

```bash
git clone --recurse-submodules <repo>
cd convex-kt
./gradlew build
```

If you cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

Requirements:

- JDK 17+ (the build targets 17).
- Android SDK 36, with `local.properties` (`sdk.dir=...`, git-ignored) or
  `ANDROID_HOME`.
- Docker, for conformance recordings.
- Node 20+, for the conformance harness.

Apple targets build only on macOS. On other hosts they are skipped.

## The standard

Every change must pass:

```bash
./gradlew spotlessCheck   # formatting (ktlint_official)
./gradlew detekt          # static analysis, including the AGENTS.md guardrails
./gradlew apiCheck        # public API surface matches the committed dumps
./gradlew check           # tests + Spotless + Detekt + Kover
./gradlew koverVerify     # coverage floor
./gradlew :tools:parity:run --args="--manifest parity.yaml"
```

`./gradlew build` runs everything. There are **no Detekt baselines**: a finding
is either fixed or the rule is changed deliberately, with a comment explaining
why.

### Changing the public API

`apiCheck` compares your code against `*/api/**/*.api`. When you intentionally
change the public surface, run `./gradlew apiDump` and commit the diff so the
change is reviewable.

### Formatting

`./gradlew spotlessApply` fixes most style issues. Spotless is the single
formatter; do not enable Detekt's `formatting` ruleset.

## Guardrails you must not break

These are enforced by the build where possible:

- Exhaustive `when` over sealed types, with **no `else`**.
- Parse backend responses by **key presence**, not nullability.
- `delay`, never `yield`, in `runTest` loops.
- KDoc on every public declaration.
- `explicitApi` stays enabled on public modules.

## Tests

- Unit tests live beside the code in `commonTest` (KMP) or `src/test` (JVM).
- Integration tests run against the **real** pinned backend; there are no mocked
  server responses in integration tests.
- Conformance tests replay the recorded fixtures in
  `conformance/fixtures/`. If a fixture disagrees with the live backend, the
  fixture is wrong — re-record it (see the `capture-conformance` skill).

## Parity

Every upstream `convex-rs` test needs a Kotlin counterpart recorded in
`parity.yaml`. The `convex-rs` source is available at
`third_party/convex-rs` (a pinned submodule). Keep the manifest and the test in
the same pull request.

## Pull requests

Use the checklist in `AGENTS.md`. Include:

- what changed and why,
- the upstream revision/evidence for ported behavior,
- any fixture or API-dump diff.
