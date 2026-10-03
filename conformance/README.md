# Conformance

Recorded WebSocket traffic used to prove the Kotlin codecs are byte-compatible
with the pinned backend, plus the harness that captures it.

## Layout

```
conformance/
├── docker-compose.yml              # pinned backend (d2ca853), SQLite
├── harness/                        # Node recorders driven by convex-js / ws
│   ├── record-handshake.mjs        # `connect-handshake`
│   ├── record-subscription.mjs     # `query-and-mutation`
│   ├── record-storage.mjs          # `storage`
│   ├── placeholders.mjs            # placeholder vocabulary for the handshake
│   └── project/                    # minimal Convex module pushed for recordings
├── fixtures/
│   ├── connect-handshake/          # raw Connect -> Ping exchange
│   ├── query-and-mutation/         # subscribe + mutate, real Transition/MutationResponse
│   ├── storage/                    # upload/download response shapes
│   └── typed-api/                  # the real backend apiSpec, used by convex-codegen
└── out/                            # scratch output (git-ignored)
```

## Rules

- Raw frames are authoritative; never hand-edit a fixture. Re-record it.
- Normalize only run-variant values, and document every substitution in the
  scenario README. Keep normalized values correctly typed so fixtures still
  decode without substitution.
- Fixtures are unit-level inputs. The real-backend run remains mandatory: CI
  re-records every scenario and fails on drift
  (`git diff --exit-code -- conformance/fixtures`).
- Fixtures are decoded by `convex-core`'s `ProtocolFixtureTest`, which runs as
  part of `./gradlew check`. The `typed-api` fixture is the exception: it is the
  backend's `apiSpec` (not WebSocket frames) and is consumed by `convex-codegen`
  and the typed-call tests.

See the `capture-conformance` skill for the full workflow.
