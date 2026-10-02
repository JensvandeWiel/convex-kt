# Conformance harness

The Node tooling that records Convex WebSocket traffic into fixtures under
`../fixtures/`. It is not part of the Kotlin build and is only run when capturing
or refreshing fixtures. See the `capture-conformance` skill.

## Requirements

- Node >= 20 (the committed recordings were captured with Node 25).
- Docker, with the pinned backend image available.
- `convex`, pinned in `package.json`. On Windows this is invoked through
  `npx.cmd`, so the recorder shells out via `cmd.exe`.

## Layout

```
harness/
├── package.json            # pinned convex + ws
├── placeholders.mjs        # placeholder vocabulary and normalization
├── record-handshake.mjs    # the `connect-handshake` scenario
└── project/                # a minimal Convex module pushed for the recording
```

## Run

```bash
npm ci
node record-handshake.mjs --url http://127.0.0.1:3210 --admin-key <admin-key>
```

Get the admin key with:

```bash
docker compose -f ../docker-compose.yml exec backend ./generate_admin_key.sh
```
