---
title: "The Android screen-inspector host is a debug provider"
date: 2026-11-22
tags: [introspection, mcp, host, android, safety]
status: accepted
---

## Context

This is the Android slice of [#3042](https://github.com/gazon1/sing/issues/3042), split out
as [#3074](https://github.com/gazon1/sing/issues/3074). The desktop host delivered by PR
#3065 exposes the screen-inspector contract as the tool-server process: the app hands an
agent a `--mcp-server` process, which builds its own Koin container, and `screen_*` tools
drive the real `TaskDetailCoordinator.onIntent`. That shape does not transfer, and the
reasons are specific:

- **There is no second process to hand over.** The Android app is one process, started by
  the launcher. Its Koin container is created in `SingularityApp.onCreate` and every screen
  object the UI uses lives in it. A "tool-server process" would be a second application
  inspecting a second graph — worse than the desktop gap, not better.
- **`--flag` and stdio do not exist.** The desktop host is opted into with a launch
  argument and speaks MCP over stdio. Neither is available to a process the launcher
  starts, and the ADRs already refuse a loopback socket as the answer.
- **The runtime was not compiled into the Android target at all.** The inspector lived in
  `shared/src/jvmMain`, so the Android build could not see it. This was the literal
  reason #3042 stayed open.

The Issue named two open questions — "the lifecycle seam … confirm before implementing" and
"the transport … decide and record the choice and threat model as an ADR". This record
answers both.

## Decision

1. **The contract is shared, the transports are not.** The inspector runtime
   (`mcp/introspection`) moved from `shared/src/jvmMain` to `shared/src/jvmAndroidMain`, a
   source set compiled into both the JVM and the Android target. It is one implementation:
   the desktop `screen_*` tools and the Android host call the same
   `ScreenInspectorRuntime`, `ScreenRegistry` and `TaskDetailScreenSession`. Two transports
   into one contract is what makes "the same logical contract" checkable rather than
   asserted.

2. **`jvmAndroidMain`, not `commonMain`.** The runtime is not platform-neutral: it takes a
   Koin container and a `TaskDetailCoordinator`, and it uses `java.util.UUID`,
   `java.util.concurrent` and `synchronized`. Making it compile for a third target would
   mean rewriting concurrency that works, for a target this project does not build — and
   `CommonMainJvmApiTest` would refuse the move, correctly. A source set that describes
   what is true (JVM bytecode, two targets) costs four lines in `shared/build.gradle.kts`
   and no rewrite.

3. **The transport is a debug-only `ContentProvider`, reached with `adb shell content
   call`.** A Provider is the only Android mechanism that answers a request synchronously
   through the binder, so one shell command is one inspect or one action and the JSON comes
   back in it. A service needs an AIDL/Messenger and a second lifetime decision; a socket is
   a network surface the desktop ADR already refused, and it would be refused here for the
   same reason. The provider is declared in `androidApp/src/debug/manifest` with
   `${applicationId}` in the authority, so a debug build installs beside a release build.

4. **The lifecycle seam is the request, and the process.** The host owns one runtime per
   process, created on the first request (`ScreenInspectorDeviceHost`) and released by its
   `stop()`. Sessions outlive a request on purpose — `open` in one call, `invoke` in the
   next is the entire workflow — which is the opposite of the CLI's per-process model, and
   correct for the opposite reason: a CLI invocation *is* its own process. There is no
   service, no background thread and no socket; work happens only while a request is being
   served.

5. **Release safety is the source set, not a flag.** The provider and everything that
   reaches it live in `androidApp/src/debug`; a release APK does not compile that directory
   at all. There is no `BuildConfig.DEBUG` branch anywhere in the path — that shape is what
   the parent ADR refuses, because a release artefact would still *carry* the capability.
   `ScreenInspectorHostIsDebugOnlyTest` asserts the boundary: the provider exists exactly
   once and only there, no `main`/`pro`/`shared` source names it, and the main manifest does
   not declare it.

6. **Caller identity is checked by UID.** The provider is `exported="true"` because `adb
   shell` is a different UID and cannot reach a private provider; `call()` then refuses
   every caller that is not the app itself, the shell (2000) or root. It is declared
   without a permission deliberately — a permission would need `pm grant` before every run,
   and the UID check is the gate that actually executes.

7. **No new MCP server and no duplicate domain tools.** The Android host serves the same
   JSON envelope as the CLI (`{"op":…}` in, `{"ok":…,"result"|"error":…}` out) and dispatches
   through `ScreenInspectorRuntime`. It is not an MCP server; it is the same contract behind
   a second transport.

## Rationale

- **Why not wire it in `SingularityApp.onCreate`, as the Issue guessed.** The Application
  class is compiled into every build variant, so host wiring there is release code — it would
  need a debug-only binding to avoid shipping, and that is a weaker boundary than "the
  directory is not compiled". The provider needs no Application change at all: it reads the
  container the Application already started, when a request arrives.
- **Why the runtime had to move rather than be copied.** A second copy under `androidMain`
  would compile, run, and be a second contract that drifts from the first. Every Android
  difference would then be invisible to the desktop tests, which is precisely the failure
  the parent Issue is about.
- **Why the boundary is static and what it does not prove.** `ScreenInspectorHostIsDebugOnlyTest`
  reads the tree; it does not assemble a release APK and inspect it. That is a stated trade:
  the static check runs in the fast lane and catches the change that would matter (moving
  the class, or calling it from `main`), while an R8-backed assertion costs a full release
  build on every run. The residual risk is a build-file change that adds `src/debug` to a
  release source set — so the test names the declarations that would have to change.
- **Why not the deep-link activity the seed fixture uses.** `DebugSeedActivity` is the
  existing debug surface and would have been the smaller change to copy. But a deep link
  returns nothing: the result would be a file read back with `run-as`, a second round trip,
  and a write path on the device. A Provider answers in one call.

## Threat model

Unchanged from the parent ADR — the opt-in does not relax it — with one platform note:

- no access to arbitrary files, no shell execution, no credentials, no unrestricted app data;
- actions stay allowlisted and validated before dispatch, with `additionalProperties: false`;
  the catalog is `ScreenRegistry`'s, the same one the desktop host serves;
- dispatch goes through the real coordinator intent path, never a bypass;
- **local only, and not a network surface**: the transport is a binder call from a same-device
  client. Nothing listens on a port, so nothing is reachable over Wi-Fi, USB-tethered IP or
  another app — the caller UID check refuses the third, and there is no address for the
  first two;
- an unknown method, an unknown `op`, a malformed body or a missing container all fail closed
  with a structured error and **no dispatch**:
  `ScreenInspectorHostContractTest` asserts each of them.

The one thing this host can do that the desktop one cannot is inspect the *live* graph — the
objects the running UI holds — because it runs in the app's own process. That is a capability
the desktop host explicitly does not have
(`2026-11-21-the-host-inspects-a-real-graph-session-not-the-window.md`), and it does not widen
the threat model: the reachable surface is still the same allowlisted screen contract, and it
is still only reachable from a debug build.

## Lifecycle

- **Start** — a debug/test build is installed and the app is running. The first request
  creates the runtime. There is no start command: entering a request is the opt-in, as
  entering the `agent` command is for the CLI.
- **Discover** — `{"op":"screens"}` and `{"op":"sessions"}`.
- **Invoke** — `{"op":"open","screen":"tasks.task-detail","params":{"taskId":…}}`, then
  `{"op":"invoke","action":…,"input":…}`, then `{"op":"wait","operationId":…}`.
- **Disconnect** — nothing to disconnect: a request ends when it returns. A session is closed
  with `{"op":"close"}`.
- **Stop** — `ScreenInspectorDeviceHost.stop()` releases the runtime and its sessions. The
  process ending does the same thing.

## Consequences

- On a debug build: `adb shell content call --uri content://com.singularity.todo.screen-inspector-host
  --method screen_inspector --arg '{"op":"screens"}'` returns the same JSON the CLI prints.
- The desktop host, the CLI and the MCP tools take the same code path they did; their files
  moved but nothing about their behavior changed, and their existing tests pass unchanged.
- A release APK has no provider, no host class and no manifest entry. The gate is a source
  set, so no launch argument, property or intent can turn it on.
- `jvmAndroidMain` now exists. It is not a general home for "code two platforms share" —
  `commonMain` is that. The declaration states the one argument that justifies it, and the
  next file that wants in has to make the same argument.
- An Android *instrumented* test that drives the provider is not added here: the contract
  tests run on the JVM against an injected container, and the device path is verified
  manually with `adb`. A Compose/instrumented test would be the next slice if the host grows
  behavior of its own.

## Links

- Issue: https://github.com/gazon1/sing/issues/3074
- Parent: https://github.com/gazon1/sing/issues/3042
- Epic: https://github.com/gazon1/sing/issues/3038
- Desktop host: `docs/decisions/2026-11-20-the-local-screen-inspector-host-is-explicit.md`
- Session scope: `docs/decisions/2026-11-21-the-host-inspects-a-real-graph-session-not-the-window.md`
- Contract: `docs/decisions/2026-11-18-the-reusable-screen-introspection-contract.md`
- Reference: `docs/architecture/screen-inspector.md`
- Implementation: `shared/src/jvmAndroidMain/kotlin/com/singularity/todo/mcp/introspection/`
  (`ScreenInspectorDeviceHost`, `ScreenInspectorHost`, `ScreenInspectorRuntime`) and
  `androidApp/src/debug/kotlin/com/singularity/todo/debug/ScreenInspectorHostProvider.kt`
