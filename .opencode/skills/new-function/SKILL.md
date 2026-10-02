---
name: New Function
description: Add a new typed Convex function descriptor to convex-kt end to end — core types, codegen, client call path, compose binding, and tests.
---

# New Function

Use this when adding a new Convex function (query, mutation, or action) or the
descriptor/typing machinery that supports one. "Function" here means the typed
Kotlin representation, the codegen mapping from `apiSpec`, and the call path —
not a Kotlin function.

## Layers

```
apiSpec JSON ──codegen──▶ ConvexFunction descriptor ──client──▶ server
                                                └──compose──▶ QueryState<T>
```

## Workflow

1. **Model the descriptor in `convex-core`.** Function kind and argument/return
   shapes are a closed set: use a `sealed interface` with a `when` that has no
   `else`. Keep the public surface `explicitApi`-compliant with KDoc.
2. **Extend codegen** (`convex-codegen`). Map the `apiSpec` entry to the
   descriptor. Add a golden test: input `apiSpec` JSON → expected Kotlin output,
   byte for byte.
3. **Wire the client path** (`convex-client`) if the kind is new. Reuse the
   existing request/response codecs; do not special-case arguments in the
   transport. Parse responses by **key presence**, not nullability.
4. **Expose a binding** (`convex-compose`) only if it is user-facing. Queries
   surface as `QueryState<T>`; mutations and actions surface as call functions.
   Keep the controller coroutine-safe and cancellable.
5. **Test.**
   - `convex-core`: codec round-trips, including 64-bit integer precision.
   - `convex-codegen`: golden file test for the new descriptor.
   - `convex-client`: conformance test against the recorded fixture
     (see the `capture-conformance` skill) or a real-backend integration test.
   - `convex-compose`: state transitions (loading → success/error) with
     `delay`-based `runTest` loops, never `yield`.
6. **Parity.** If the behavior corresponds to an upstream `convex-rs` test, add
   it to `parity.yaml` (see the `check-parity` skill).
7. **Verify.** `./gradlew build` and the parity check.

## Rules

- No `else` over sealed function/argument/result types.
- No `Any`/`Map<String, Any>` in the public call path; the point is typing.
- Unknown `apiSpec` fields must fail loudly (forward-compatibility signal), not
  be silently dropped. Note the searched sources before declaring a field
  unknown.
- Public declarations get KDoc explaining why the shape exists.
