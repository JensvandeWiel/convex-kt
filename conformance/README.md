# Conformance fixtures

This directory will hold the recorded WebSocket traffic used to prove wire
compatibility, plus the harness that captures it. It is populated in plan step 3
and used by the `capture-conformance` skill.

Planned layout:

```
conformance/
├── docker-compose.yml            # pinned backend (d2ca853), SQLite storage
├── harness/                      # Node + convex-js scenario drivers
└── fixtures/
    └── <scenario>/
        ├── client-to-server.ndjson
        ├── server-to-client.ndjson
        └── README.md             # pin + normalization decisions
```

Rules:

- Raw frames are authoritative; never hand-edit them.
- Normalize only non-deterministic values (session ids, tokens, timestamps) and
  document every substitution in the scenario README.
- Never commit live secrets.
- Fixtures are unit-level inputs. The real-backend integration run remains
  mandatory (see `AGENTS.md`, hard rule 3).
