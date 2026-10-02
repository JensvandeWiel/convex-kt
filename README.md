# convex-kt

A clean, idiomatic Kotlin Multiplatform client for [Convex](https://convex.dev),
targeting **Android**, **Windows desktop (JVM)**, and **iOS**.

Built with Compose Multiplatform, Coroutines (`Flow`), and a direct WebSocket
transport. No Rust FFI.

> **Status: project scaffold.** The build, module skeleton, CI, parity gate, and
> agent tooling are in place. The protocol implementation lands in the plan's
> later steps. See `AGENTS.md` for the working agreement.

## Modules

| Module | Responsibility |
| --- | --- |
| `convex-core` | Pure values, JSON codecs (64-bit precision preserved), protocol messages, sync state machine |
| `convex-client` | Ktor WebSocket transport + client driving the state machine |
| `convex-auth` | Token lifecycle and JWT verification (RS256 + ES256) |
| `convex-storage` | File upload, download, and URL generation (the only HTTP module) |
| `convex-compose` | `QueryState<T>` controllers and `@Composable` bindings |
| `convex-codegen` | Build-time `apiSpec` → typed `ConvexFunction` generator |
| `tools/parity` | CI validator for `parity.yaml` |

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

## Parity gate

Every upstream `convex-rs` test is tracked in `parity.yaml`. Validate it with:

```bash
./gradlew :tools:parity:run --args="--manifest parity.yaml"
```

## Documentation

- `AGENTS.md` — hard rules, guardrails, and Rust → Kotlin mapping.
- `.opencode/skills/` — workflows: `port-from-rust`, `capture-conformance`,
  `check-parity`, `new-function`.

## License

Apache License 2.0. See `LICENSE`. All source files carry the standard header,
enforced by Spotless.
