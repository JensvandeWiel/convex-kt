# convex-kt

A clean, idiomatic Kotlin Multiplatform client for [Convex](https://convex.dev),
targeting **Android**, **Windows desktop (JVM)**, and **iOS**.

Built with Compose Multiplatform, Coroutines (`Flow`), and a direct WebSocket
transport. No Rust FFI.

> **Status: implementation complete, hardening in progress.** All modules are
> implemented and tested: the value codec and sync state machine, the client and
> Ktor transport, auth, storage, Compose bindings, and codegen. The build, CI,
> parity gate, and agent tooling are in place. See `AGENTS.md` for the working
> agreement and `docs/` for design records.

## Modules

| Module | Responsibility |
| --- | --- |
| `convex-core` | Pure values, JSON codecs (64-bit precision preserved), protocol messages, sync state machine, function descriptors |
| `convex-client` | Ktor WebSocket transport + client driving the state machine |
| `convex-auth` | JWT parsing and RS256/ES256 verification |
| `convex-storage` | File upload, download, and URL generation (the only HTTP module) |
| `convex-compose` | `QueryState<T>` controllers and `@Composable` bindings |
| `convex-codegen` | Build-time `apiSpec` → typed `ConvexFunction` generator |
| `tools/parity` | CI validator for `parity.yaml` |
| `examples/chat` | Compose Desktop chat example against a live backend |

## Build

```bash
./gradlew build          # compile, tests, lint
./gradlew checkAll       # full quality gate (formatting, analysis, API, coverage)
```

On Windows use `.\gradlew.bat`. Clone with `--recurse-submodules` (the upstream
`convex-rs` source lives at `third_party/convex-rs`). Android needs
`local.properties` (git-ignored) or `ANDROID_HOME`. Apple targets only build on
macOS.

## Quality gates

| Gate | Command | Standard |
| --- | --- | --- |
| Formatting | `./gradlew spotlessCheck` | Spotless + `ktlint_official` |
| Static analysis | `./gradlew detekt` | Detekt, `config/detekt/detekt.yml`, no baselines |
| Public API | `./gradlew apiCheck` | binary-compatibility-validator dumps |
| Coverage | `./gradlew koverVerify` | Kover, floor ratcheted over time |

See `CONTRIBUTING.md` for the full standard and `AGENTS.md` for which guardrails
are mechanically enforced versus reviewed by a human.

When a rule rejects a genuinely correct pattern, suppress it **inline** at the
narrowest scope **with a comment explaining why**, rather than weakening the
shared ruleset. Relaxing a rule in `config/detekt/detekt.yml` is a last resort,
because it silences that rule for every module. See "When a rule blocks
legitimate code" in `CONTRIBUTING.md`.

## Example

`examples/chat` is a Compose Desktop app that subscribes to `messages:list`
and sends `messages:send`:

```bash
CONVEX_URL=http://127.0.0.1:3210 ./gradlew :examples:chat:run
```

## Parity gate

Every upstream `convex-rs` test is tracked in `parity.yaml`. This is enforced at
test time: `ParityCoverageTest` scans the `third_party/convex-rs` submodule
during `./gradlew check` and fails if any upstream test is unaccounted for. The
same logic is available as a CLI:

```bash
./gradlew :tools:parity:run --args="--upstream third_party/convex-rs"
```

To absorb a new upstream revision, append the generated entries:

```bash
./gradlew :tools:parity:run --args="--emit-missing third_party/convex-rs"
```

## Documentation

- `AGENTS.md` — hard rules, guardrails, and Rust → Kotlin mapping.
- `.opencode/skills/` — workflows: `port-from-rust`, `capture-conformance`,
  `check-parity`, `new-function`.

## License

Apache License 2.0. See `LICENSE`. All source files carry the standard header,
enforced by Spotless.
