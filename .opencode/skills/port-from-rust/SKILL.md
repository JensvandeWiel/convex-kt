---
name: Port from Rust
description: Port behavior and tests from get-convex/convex-rs into idiomatic Kotlin in convex-kt, keeping parity.yaml in sync.
---

# Port from Rust

Use this when implementing Kotlin behavior that already exists in
`get-convex/convex-rs` (protocol handling, sync state machine, auth, storage).
The goal is behavioral parity with an idiomatic Kotlin shape, not a line-by-line
transliteration.

## Preconditions

1. Work from a concrete upstream revision, never from memory. Record it:
   `git -C <convex-rs-checkout> rev-parse HEAD`.
2. Find the exact source and test. If you cannot, write down what you searched
   (local files, both upstream repos, docs/specs) before concluding anything.
   Never assume a detail is absent from a local-only search.
3. Read `AGENTS.md` § "Development guardrails" and § "Rust → Kotlin mapping".

## Workflow

1. **Locate.** Identify the Rust type/function and the test(s) that prove it.
2. **Choose the module.** Follow the dependency direction in `AGENTS.md`:
   values/codecs/state machine → `convex-core`; transport → `convex-client`;
   auth → `convex-auth`; files → `convex-storage`; UI → `convex-compose`.
3. **Model the types.** Turn Rust enums with data into `sealed interface` /
   `sealed class`. Never a plain class or a generic tagged union.
4. **Map the mutation.** A `&mut self` method becomes a pure function returning a
   new value. Make sure the Kotlin caller assigns and uses the result — an
   ignored return value means the state machine silently stops advancing.
5. **Branch exhaustively.** Replace `match` with a `when` that has **no `else`**.
   If you feel the need for `else`, the type is not modeled as a closed
   hierarchy yet.
6. **Handle errors.** `Result<T, E>` becomes exceptions (or `Either`). Make sure
   a thrown `ConvexError` cannot leave a mutation dangling for the caller.
7. **Watch the wire format.** `u64` timestamps are base64-encoded little-endian
   on the wire, not JSON numbers. Keep the decode/encode pair together and
   tested.
8. **Port the test.** Every upstream test gets a Kotlin counterpart that asserts
   the same behavior. Inside `runTest`, loop with `delay`, never `yield`.
9. **Record parity.** Add or update the entry in `parity.yaml`
   (see the `check-parity` skill) with the upstream reference and the Kotlin
   test reference.
10. **Verify.** Run `./gradlew build` (or at least the affected module's tests)
    and `./gradlew :tools:parity:run --args="--manifest parity.yaml"`.

## Definition of done

- Upstream source revision and test are cited in the PR description.
- Kotlin test asserts the same observable behavior as the upstream test.
- `parity.yaml` entry moves to `ported` (or is added as `ported`).
- No `else` over a sealed type; public APIs have KDoc; `./gradlew build` is green.
