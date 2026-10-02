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
  Ktor 3.3.0 (see `gradle/libs.versions.toml`)

## The three hard rules

Non-negotiable. Where possible they are enforced by tooling.

1. **Full test-suite parity.** Every upstream `convex-rs` test has a Kotlin
   counterpart, tracked in `parity.yaml`. `:tools:parity` validates that
   manifest, and CI fails when it drifts. Never port upstream logic without
   adding the manifest entry in the same change.
2. **Automated upstream detection.** A watcher monitors the
   `get-convex/convex-backend` and `get-convex/convex-rs` releases and diffs
   protocol paths. It **only drafts issues**. It never auto-merges and never
   edits code.
3. **Real backend integration.** Integration tests run per-commit against the
   pinned local Docker Compose backend using SQLite. There are no mocked server
   responses in integration tests. Recorded frames are fixtures for unit-level
   conformance, not a replacement for the live run.

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
convex-core      pure values, JSON codecs, protocol messages, sync state machine
convex-client    WebSocket transport + client driving the state machine
convex-auth      token lifecycle + JWT verification (RS256, ES256)
convex-storage   file upload/download/URL generation (the only HTTP module)
convex-compose   QueryState<T> controllers + @Composable bindings
convex-codegen   build-time apiSpec -> ConvexFunction descriptor generator
tools/parity     JVM CLI that validates parity.yaml in CI
```

Dependencies point inward: `convex-core` depends on nothing in this repository.
`convex-client`, `convex-auth`, and `convex-storage` depend on `convex-core`.
`convex-compose` depends on `convex-core` and `convex-client`. `convex-codegen`
and `tools/parity` are JVM-only build tools. Do not introduce a dependency that
points the other way.

## Build and test

Use the wrapper. Android requires `local.properties` (git-ignored) or
`ANDROID_HOME`; the wrapper itself needs no other setup.

```bash
# everything: compile, tests, lint
./gradlew build

# just the checks
./gradlew check

# one module's JVM tests
./gradlew :convex-core:jvmTest

# the parity gate
./gradlew :tools:parity:run --args="--manifest parity.yaml"
```

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
