# AGENTS.md — working agreement for `convex-kt`

This file is the contract for anyone — human or AI — changing this repository.
Read it before writing code. The four skills under `.opencode/skills/` are the
executable form of the workflows described here; load the relevant skill when
doing that kind of work.

## What we are building

`convex-kt` is a clean, idiomatic Kotlin Multiplatform client for
[Convex](https://convex.dev), targeting:

- Android
- Windows desktop (JVM)
- iOS

It uses Compose Multiplatform, Coroutines / `Flow`, and a direct WebSocket
transport implementation. It deliberately does **not** use Rust FFI: the wire
protocol is reimplemented in Kotlin and proven against recorded fixtures.

- Group id: `eu.wynq.convex`
- Kotlin targets: `androidTarget`, `jvm`, `iosX64`, `iosArm64`, `iosSimulatorArm64`
- Pinned toolchain: Gradle 9.1, Kotlin 2.2.21, AGP 8.13.2, Compose MP 1.9.3,
  Ktor 3.3.0 (see `gradle/libs.versions.toml`; Dokka 2.2.0 is pinned in
  `buildSrc` because the convention plugins apply it)

## Quality standard

The project holds itself to the standard below. Every pillar is wired into the
build and CI; none is aspirational.

| Pillar | Enforced by | Gate |
| --- | --- | --- |
| Formatting | Spotless, `ktlint_official` | `spotlessCheck` |
| Static analysis | Detekt, shared `config/detekt/detekt.yml` | `detekt` |
| Coverage | Kover, floor currently 20% | `koverVerify` |
| Public API | binary-compatibility-validator, dumps in `*/api/` | `apiCheck` |
| Docs | Dokka on public modules | `dokkaGenerateHtml` |
| Tests | Kotlin test + JUnit | `check` |

Run everything at once:

```bash
./gradlew checkAll
```

There are **no Detekt baselines.** A finding is fixed, or the rule is changed
deliberately with a comment. Kover's floor starts low because the protocol
implementation is still being built; it is ratcheted upward as `convex-core`
lands. `apiCheck` means an intentional public-surface change requires a
committed `apiDump` diff.

### When a rule blocks legitimate code

Static analysis serves the code, not the other way around. If a rule rejects a
pattern that is genuinely correct, in order of preference:

1. **Fix the code.** Almost always the right answer.
2. **Inline `@Suppress`, scoped to the narrowest declaration, with a comment
   naming the false positive and the pattern that is actually correct.**
   This is the preferred escape hatch: the suppression applies only where the
   exception exists, so every other file keeps the full-strength rule.
3. **Relax that specific rule in `config/detekt/detekt.yml`, with a comment.**
   Last resort, because a config relaxation is a **global blind spot**: it
   silences that rule for every module, including code where it was working. Use
   it only when the rule is wrong in principle (in which case say so), never to
   silence one awkward call site.

A suppression or relaxation without a written reason is a review defect, not a
style preference. Never add a Detekt baseline or lower the Kover floor to
unblock a change.

When suppressing, keep it legible:

```kotlin
// Convex encodes 64-bit timestamps as base64 little-endian; 8 is the width of
// the payload, not a tunable. MagicNumber cannot express that.
@Suppress("MagicNumber")
```

### Which rules are actually enforced

`AGENTS.md` describes more discipline than any tool can check. To avoid implying
guarantees the build cannot make, here is the honest mapping:

| Guardrail | Enforced today? | By what |
| --- | --- | --- |
| `explicitApi` | Yes | Kotlin compiler |
| KDoc on public declarations | Yes | Detekt `UndocumentedPublic*` |
| No `else` over sealed types | Partly | Detekt `ElseCaseInsteadOfExhaustiveWhen`; the compiler enforces exhaustiveness, but recognizing a *stray* `else` is a review duty |
| Key presence over key value | No | Review only — no rule can infer intent |
| `delay`, never `yield`, in `runTest` | Partly | Detekt coroutines rules flag risky patterns; the `yield`-loop case is review |
| Wire/codec correctness | Yes | Conformance fixtures + real-backend CI |
| Test-suite parity | Yes | `ParityCoverageTest` scans the convex-rs submodule each `check` |

Anything marked "No" or "Partly" is a review responsibility, not a build failure.

## The three hard rules

Non-negotiable. Where possible they are enforced by tooling.

1. **Full test-suite parity.** Every upstream `convex-rs` test has a Kotlin
   counterpart, tracked in `parity.yaml`. `ParityCoverageTest` scans the
   `third_party/convex-rs` submodule on every `./gradlew check` and fails if any
   upstream test is unaccounted for, so drift cannot merge silently. Never port
   upstream logic without updating the manifest in the same change.
2. **Automated upstream detection.** A watcher monitors the
   `get-convex/convex-backend` and `get-convex/convex-rs` releases and diffs
   protocol paths. It **only drafts issues**. It never auto-merges and never
   edits code.
3. **Real backend integration.** `:integration-tests` boots the pinned backend
   with Testcontainers, deploys the conformance project into it, and exercises
   the real feature set — subscription, mutation, action, auth, storage,
   optimistic updates, and the HTTP API — with no mocked server responses.
   Recorded frames are fixtures for fast unit-level conformance; they never
   replace the live run.

## Development guardrails

- **Exhaustive `when`.** Model protocol and domain variants with
  `sealed interface` / `sealed class` and branch with exhaustive `when`
  expressions that have **no `else`**. Adding a protocol variant must break
  compilation until every branch handles it. The only acceptable `else` is for
  genuinely open types such as `String` or platform types.
- **Key presence over key value.** When parsing backend responses, branch on the
  *presence* of a key (for example `errorData`) rather than on a nullable value.
  Present-but-null and absent are different protocol states.
- **Test timeouts.** Inside `runTest`, use `delay`, never `yield`, for loops
  that wait on another coroutine. A `yield` loop can spin forever in the test
  scheduler and hang CI.
- **KDoc is mandatory.** Every public declaration carries KDoc, generated with
  Dokka. KDoc explains *why*; the signature already explains *what*.
- **`explicitApi`.** The `convex-kmp-library` and `convex-jvm-library`
  convention plugins enable `explicitApi()`. Accidental public surface fails the
  build.
- **Never assume absence from a local search.** If a protocol detail is not in
  this repository, say what you searched (local files, both upstream Rust repos,
  OpenAPI/bigint specifications) and mark the detail unknown. Do not assume it
  does not exist.

## Protocol facts discovered from the pinned backend

These were learned by driving the real backend, not read off a type definition.
See `conformance/fixtures/connect-handshake/README.md` for the raw evidence.

- **Wire keys are camelCase.** `convex-rs` declares `session_id`, but the JSON
  on the wire is `sessionId`; the serializer renames fields. A snake_case key is
  rejected with `missing field 'sessionId'`.
- **`Connect.sessionId` must be a UUID.** An opaque token fails with
  `invalid length: found 6`.
- **`Connect.connectionCount` is required**, despite looking optional in the type
  model.
- **A fresh session's first server frame is `Ping`, not `Connected`.** Do not
  infer wire behavior from the `ServerMessage` enum: a variant existing in Rust
  does not mean it is emitted on this path.

When in doubt, re-record against the pinned backend rather than extrapolating
from `convex-rs` source.

## Rust → Kotlin mapping

When porting `convex-rs` logic, use these translations. Do **not** blindly
translate lifetimes, `Arc`/`Rc`/`Box`, `RefCell`, or `Mutex` unless genuine
cross-thread synchronization is required.

| Rust concept | Kotlin translation | Trap to avoid |
| --- | --- | --- |
| `&mut self` method | Pure function returning a new value | Ensure the Kotlin caller assigns and uses the returned value; otherwise the state machine never advances. |
| `enum` with data | `sealed interface` / `sealed class` | Do not use plain classes or generic tagged unions. |
| `match` | Exhaustive `when` expression | Do not include an `else` branch. |
| `.await` in a loop | `suspend` / `Channel.receive()` | Ensure another coroutine can run to produce what the loop waits for, or it deadlocks. |
| `Result<T, E>` | Exceptions (or `Either`) | Unhandled `ConvexError` mutations hang the caller. |
| `u64` timestamps | `Long` | The wire format is base64-encoded little-endian, *not* a JSON number. |
| `Vec<T>` / `&str` | `List<T>` / `String` | Use standard Kotlin collections. |

## Module map and dependency direction

```
convex-core      pure values, JSON codecs, protocol messages, sync state machine, function descriptors
convex-client    Ktor WebSocket transport + client driving the state machine
convex-auth      JWT parsing and RS256/ES256 verification
convex-storage   file upload/download/URL generation (the only HTTP module)
convex-compose   QueryState<T> controllers + @Composable bindings
convex-codegen   apiSpec -> ConvexFunction/descriptor generator (JVM)
convex-codegen-gradle  Gradle plugin: captures the apiSpec and runs `convex-codegen` per build
tools/parity     JVM CLI that validates parity.yaml in CI
examples/chat    Compose Desktop example; depends on client + compose
```

Dependencies point inward: `convex-core` depends on nothing in this repository.
`convex-client`, `convex-auth`, and `convex-storage` depend on `convex-core`.
`convex-compose` depends on `convex-core` and `convex-client`. `convex-codegen`
is a JVM-only build tool; `convex-codegen-gradle` wraps it in a Gradle plugin and
depends on it. `tools/parity` is a JVM CLI, and `examples/chat` is a leaf.
Do not introduce a dependency that points the other way.

See `README.md` for the module map and consumer-facing usage, and
`CONTRIBUTING.md` for the contributor workflow.

## Build and test

Use the wrapper. Android requires `local.properties` (git-ignored) or
`ANDROID_HOME`; the wrapper itself needs no other setup.

```bash
# everything: compile, tests, lint, analysis, API, coverage
./gradlew build

# the full quality gate (all pillars + every module's tests)
./gradlew checkAll

# formatting, static analysis, API surface, coverage individually
./gradlew spotlessCheck
./gradlew detekt
./gradlew apiCheck
./gradlew koverVerify

# fix formatting
./gradlew spotlessApply

# one module's JVM tests
./gradlew :convex-core:jvmTest

# the parity gate (structural validation)
./gradlew :tools:parity:run --args="--manifest parity.yaml"

# parity coverage: every upstream test must be accounted for
./gradlew :tools:parity:run --args="--upstream third_party/convex-rs"

# integration tests against a real backend (needs Docker + Node)
./gradlew :integration-tests:integrationTest

# absorb a new upstream revision: append the emitted entries to parity.yaml
./gradlew :tools:parity:run --args="--emit-missing third_party/convex-rs"
```

The upstream `convex-rs` source is available as a git submodule at
`third_party/convex-rs`, pinned to a reviewed commit. Clone with
`--recurse-submodules`, or run `git submodule update --init --recursive`.

On Windows use `.\gradlew.bat` instead of `./gradlew`. Apple targets are
declared everywhere but only build on macOS; on other hosts they are skipped
(`kotlin.native.ignoreDisabledTargets=true`).

## Adding work

- Porting upstream logic → load the **`port-from-rust`** skill.
- Recording backend frames → load the **`capture-conformance`** skill.
- Checking or updating the manifest → load the **`check-parity`** skill.
- Adding a Convex function/descriptor → load the **`new-function`** skill.

## Pull request checklist

- [ ] Tests added for every behavior change; parity entry updated.
- [ ] No `else` on a `when` over a sealed type.
- [ ] Parsing branches on key presence, not nullability.
- [ ] `runTest` loops use `delay`, not `yield`.
- [ ] Public declarations have KDoc.
- [ ] `./gradlew build` is green locally.
- [ ] Version pins changed only with a matching parity/upstream note.

## Version pinning policy

Versions in `gradle/libs.versions.toml` are pinned deliberately. The client must
behave byte-identically against the pinned backend, so dependency drift is a
bug, not an upgrade. Upstream release changes are surfaced by the watcher as
issues and applied through review.
