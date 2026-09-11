# Eclipse OCT

Eclipse plugin for Open Collaboration Tools (OCT): live-shares an Eclipse
workspace with peers via a native `oct-service-process` subprocess bridging to
the `open-collaboration-tools` TypeScript protocol implementation. Java 21,
Maven 3.9+/Tycho 4.0.8, Eclipse 2025-03 target platform.

**Requires a sibling checkout of `open-collaboration-tools`** (defaults to
`../open-collaboration-tools`, override with `-Doct.project.path=...`) and
Node.js 20+ on `PATH` — the build compiles and stages its native executable.

## Commands

Run from the repo root.

```sh
mvn install -DskipTests               # one-time reactor install, ~25s fresh — needed before any single-module command below
mvn clean verify                      # full build + native executable + all tests, ~50s
mvn -pl org.eclipse.oct.tests -am -Dtycho.mode=maven test -Dtest=<ClassName>   # single test class, ~9s once installed
```

- There is no separate lint/typecheck command — `mvn clean verify` (Tycho
  compiler + PDE manifest checks) is the full verification.
- **`mvn clean verify` currently fails on a fresh checkout** (verified
  2026-09-11): ~29 integration-test errors in `org.eclipse.oct.tests`, two
  distinct causes — a JUnit Jupiter version mismatch (`AbstractMethodError` in
  parameterized tests) and `JsonRpcException: Stream closed` from the native
  `oct-service-process` in handshake/awareness tests. Not yet root-caused;
  don't assume a red run means your change broke something until you've
  compared against a clean-checkout baseline run. Tracked as an open item —
  see [docs/exec-plans/](docs/exec-plans/active/).
- The `-Dtycho.mode=maven` single-test form needs `org.eclipse.oct` already
  built and installed to the local `.m2` repo (via `mvn install -DskipTests`
  or a prior full build) — it fails with "Missing requirement: osgi.bundle;
  org.eclipse.oct" otherwise, since Tycho resolves module deps from the
  reactor/local repo, not the workspace.

## Why and where

- `org.eclipse.oct/` — the plugin bundle; all Java sources under `src/`, plus
  `lib/` (pre-downloaded third-party jars) and `oct-bin/` (native executable,
  staged by the build — never hand-edit either).
- `org.eclipse.oct.tests/` — JUnit 5 integration tests (Tycho
  `eclipse-test-plugin`); spin up a real OCT server + native service process
  and drive the plugin's actual internal classes.
- `org.eclipse.oct.feature/`, `org.eclipse.oct.repository/` — Eclipse feature
  and p2 update site packaging.
- `org.eclipse.oct.target/` — the pinned target platform (Eclipse 2025-03).
- See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the package layout
  inside `org.eclipse.oct/src` and the host/guest role split.

## Conventions

- `internal/` (under `org/eclipse/oct/`) holds the RPC transport, EFS, and
  auth implementations — don't add new public API that depends on
  `internal.*` types from outside their own package tree.
- Java sources carry the `Copyright (c) <year> TypeFox GmbH and others.` /
  `SPDX-License-Identifier: EPL-2.0` header — copy it from a neighbouring file.

## Boundaries and definition of done

- Never hand-edit `lib/`, `oct-bin/`, or anything under `target/` —
  regenerate with `mvn clean verify`.
- The plugin must not defeat the OCT protocol's end-to-end encryption (e.g. by
  logging decrypted payloads anywhere the relay server could read them) — see
  [ADR-0001](docs/adr/0001-native-service-process-bridge.md).
- Done means `mvn clean verify` passes locally (or, given the currently-known
  failure above, that you've confirmed your change didn't introduce *new*
  failures beyond that baseline), with output shown.
- A change to the sync/save message flow (`editor/`, `internal/fs/`,
  `internal/rpc/`) updates
  [org.eclipse.oct/docs/sync-and-save-lifecycle.md](org.eclipse.oct/docs/sync-and-save-lifecycle.md)
  in the same change.
- If reality contradicts this file or `docs/`, fix the doc in the same change
  — never work around it silently.

## Pointers

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module layering, native
  process boundary, host/guest roles, invariants.
- [docs/adr/](docs/adr/) — do not contradict accepted ADRs: ADR-0001 (native
  service-process bridge instead of a Java protocol reimplementation),
  ADR-0002 (gate outgoing edits on seed confirmation, with a timeout
  fallback).
- [docs/exec-plans/](docs/exec-plans/active/) — multi-session work; move to
  `completed/` when done.
- [org.eclipse.oct/docs/sync-and-save-lifecycle.md](org.eclipse.oct/docs/sync-and-save-lifecycle.md)
  — editor/file sync trigger→RPC→handler tables, save-path behavior, known
  upstream gaps.
- [README.md](README.md) — human-facing PDE import / "Run As Eclipse
  Application" / update-site install instructions.
