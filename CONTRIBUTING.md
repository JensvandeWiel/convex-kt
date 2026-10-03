# Contributing to convex-kt

Thanks for helping. This file is the practical guide to *how the code is
structured and how to change it*. `AGENTS.md` is the authoritative working
agreement and wins if anything here disagrees; read it first, especially the
three hard rules and the Rust → Kotlin mapping.

## Setup

```bash
git clone --recurse-submodules <repo>
cd convex-kt
./gradlew build
```

If you cloned without submodules: `git submodule update --init --recursive`.

Requirements:

- JDK 17+ (the build targets 17).
- Android SDK 36, with `local.properties` (`sdk.dir=...`, git-ignored) or
  `ANDROID_HOME`.
- Docker and Node 20+ for conformance recordings and integration tests.

Apple targets build only on macOS; other hosts skip them.

## How the code is organized

Dependencies point one way. Do not add a dependency that points back:

```
convex-core      pure values, JSON codecs, protocol messages, sync state machine, descriptors
convex-client    Ktor WebSocket transport + the client driving the state machine
convex-auth      JWT parsing and RS256/ES256 verification
convex-storage   file upload/download (the only module that owns an HTTP client)
convex-compose   QueryState<T> controllers + @Composable bindings
convex-codegen   build-time apiSpec -> descriptor generator (JVM)
tools/parity     JVM CLI that validates parity.yaml
examples/chat    Compose Desktop example (leaf)
```

When adding a feature, put it in the lowest module that can own it:

- Pure data, wire shapes, and reduction logic → `convex-core`.
- Connection, coroutines, retries, and call orchestration → `convex-client`.
- Compose lifecycle and state → `convex-compose`.
- Generated call sites → `convex-codegen`.

`convex-core` holds **no coroutines**: the state machine is a synchronous
reducer, which is what makes it testable without a network.

## Coding conventions

The build enforces most of this; these are the ones that need a human.

- **Exhaustive `when` over sealed types, no `else`.** Model variants with
  `sealed interface`/`sealed class`; adding a variant must break compilation
  until every branch handles it. `else` is only for genuinely open types.
- **Key presence over key value.** When parsing backend responses, branch on
  whether a key is *present* (`errorData`) rather than on a nullable value;
  present-but-null is a different protocol state.
- **`delay`, never `yield`, in `runTest`.** A `yield` loop can spin forever in
  the test scheduler and hang CI.
- **KDoc on every public declaration.** It explains *why*; the signature says
  *what*.
- **`explicitApi`.** The library conventions enable it, so accidental public
  surface fails the build.
- **Public API changes require `./gradlew apiDump`** and a committed diff.
- **Never assume absence from a local search.** If a protocol detail is not in
  the repo, say what you searched and mark it unknown.
- **Wire facts are learned from the backend, not inferred.** Prefer re-recording
  over extrapolating from `convex-rs` source.

When a Detekt rule rejects a genuinely correct pattern, fix the code first;
otherwise add a narrowly scoped `@Suppress` with a comment. There are **no
Detekt baselines**, and a config relaxation is a last resort because it blinds
the rule globally.

## Adding a Convex function or typed call

Typed calls are generated from the backend's `apiSpec`. The pieces are:

1. `convex-core`: `ConvexValidator`, `ConvexFunction`, and the descriptor
   families `ConvexQuery`/`ConvexMutation`/`ConvexAction` with `encodeArguments`.
2. `convex-client`: the typed `subscribe`/`mutate`/`action` overloads.
3. `convex-compose`: the typed `rememberQuery` overloads.
4. `convex-codegen`: `ApiSpecParser` and `KotlinSourceGenerator`.

To regenerate the committed fixture used by the tests:

```bash
./gradlew :convex-codegen:run --args="--spec conformance/fixtures/typed-api/api-spec.json --package eu.wynq.convex.client.generated --object GeneratedApi --out convex-client/src/commonTest/kotlin/eu/wynq/convex/client/generated/GeneratedApi.kt"
```

Generator output must be **deterministic** (it is committed and diffed). If you
change the generator, regenerate the fixture and confirm the diff is only what
you intended.

### Fetching a real `apiSpec`

The fixture is captured from a running backend, not hand-written:

1. `docker compose -f conformance/docker-compose.yml up -d`.
2. Deploy the harness project:
   ```
   CONVEX_SELF_HOSTED_URL=http://127.0.0.1:3210 \
   CONVEX_SELF_HOSTED_ADMIN_KEY=<key> npx convex deploy -y   # from conformance/harness/project
   ```
   Get `<key>` with
   `docker compose -f conformance/docker-compose.yml exec backend ./generate_admin_key.sh`.
3. Query `_system/cli/modules:apiSpec` and write the response's `value` array
   verbatim to `conformance/fixtures/typed-api/api-spec.json`.

Never hand-edit a captured fixture; re-capture it. See the `capture-conformance`
skill for the general fixture workflow.

## Tests

- **Unit tests** live beside the code in `commonTest` (KMP) or `src/test` (JVM).
- **Conformance tests** replay the recorded fixtures in `conformance/fixtures/`.
  Raw frames are authoritative: if a fixture disagrees with the live backend, the
  fixture is wrong — re-record it.
- **Integration tests** (`:integration-tests`) boot the pinned backend with
  Testcontainers and deploy the conformance project. There are no mocked server
  responses; every feature is exercised against the real server.
- **Parity**: every upstream `convex-rs` test is tracked in `parity.yaml` and
  `ParityCoverageTest` fails if one is missing. Use the `check-parity` skill.

A behavior change needs a test in the same change. For wire behavior, prefer a
conformance fixture plus a real-backend integration assertion.

## Quality gates

```bash
./gradlew checkAll        # everything: format, analysis, API, coverage, tests
./gradlew spotlessApply   # fix formatting
./gradlew spotlessCheck detekt apiCheck koverVerify
./gradlew :tools:parity:run --args="--upstream third_party/convex-rs"
./gradlew :integration-tests:integrationTest     # needs Docker and Node
```

On Windows use `.\gradlew.bat`. A change is ready when `checkAll` is green.

## Pull requests

Use the checklist in `AGENTS.md`. Include what changed and why, the upstream
revision/evidence for ported behavior, and any fixture or API-dump diff.
