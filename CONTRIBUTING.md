# Contributing to convex-kt

`AGENTS.md` is the authoritative working agreement and wins if anything here
disagrees. This file is only the practical path for making a change.

## Before you start

Read, in order:

1. `AGENTS.md` — the three hard rules, development guardrails, and the
   Rust → Kotlin mapping.
2. `README.md` — the module map.
3. The relevant skill under `.opencode/skills/` (`port-from-rust`,
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
- Docker and Node 20+, for conformance recordings and the integration tests.

Apple targets build only on macOS; other hosts skip them.

## Workflow

```bash
./gradlew build           # compile, tests, formatting, analysis, API, coverage
./gradlew checkAll        # the same gates across every module
./gradlew spotlessApply   # fix formatting
```

On Windows use `.\gradlew.bat`. To run the individual gates, or the parity and
integration checks:

```bash
./gradlew spotlessCheck detekt apiCheck koverVerify
./gradlew :tools:parity:run --args="--upstream third_party/convex-rs"
./gradlew :integration-tests:integrationTest     # needs Docker and Node
```

There are **no Detekt baselines**: a finding is either fixed or the rule is
changed deliberately, with a reason. See "When a rule blocks legitimate code" in
`AGENTS.md` for the policy.

### Changing the public API

`apiCheck` compares your code against `*/api/**/*.api`. When you intentionally
change the public surface, run `./gradlew apiDump` and commit the diff so the
change is reviewable.

## Tests

- Unit tests live beside the code in `commonTest` (KMP) or `src/test` (JVM).
- Integration tests run against the **real** pinned backend; there are no mocked
  server responses in integration tests.
- Conformance tests replay the recorded fixtures in `conformance/fixtures/`. If
  a fixture disagrees with the live backend, the fixture is wrong — re-record it
  (see the `capture-conformance` skill).
- Every upstream `convex-rs` test needs a counterpart in `parity.yaml`; the
  `check-parity` skill covers the mechanics.

## Pull requests

Use the checklist in `AGENTS.md`. Include:

- what changed and why,
- the upstream revision/evidence for ported behavior,
- any fixture or API-dump diff.
