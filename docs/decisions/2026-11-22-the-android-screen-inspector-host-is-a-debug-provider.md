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

5. **Release safety is the source set, and the line is where the entry point is.** The
   provider, its manifest entry, and the process host that hands a request to a container
   (`ScreenInspectorDeviceHost`) all live in `androidApp/src/debug`; a release APK does not
   compile that directory at all. There is no `BuildConfig.DEBUG` branch anywhere in the
   path — that shape is what the parent ADR refuses, because a release artefact would still
   *carry* the capability.

   **One honest limitation, stated rather than glossed.** The contract itself
   (`ScreenInspectorHost` — JSON in, JSON envelope out) is compiled into the Android release
   variant, because it is shared with the JVM target and that is the point of the split. A
   release APK therefore *contains* `ScreenInspectorHost`, `ScreenInspectorRuntime` and the
   screen registry. What it does not contain is any way to reach them: no provider, no
   manifest entry, no `ScreenInspectorDeviceHost`, so nothing in a release build ever
   constructs a runtime and no external caller has an entry point. "Capability absent" is
   the accurate claim; "no host classes in the APK" is not, and the first revision of this
   record overstated it (both independent reviewers caught it — see the review notes below).

   `ScreenInspectorHostIsDebugOnlyTest` asserts the boundary: the provider exists exactly
   once and only in `src/debug`, the transport entry point is named by no release source
   set, the main manifest does not declare the provider, and — the anti-vacuity check — the
   gate's list of release source sets covers every `src/*Main` set the build declares. That
   last assertion exists because the first revision's hand-written list silently omitted
   `jvmAndroidMain`, which is exactly where the entry point lived.

6. **Caller identity is checked by UID.** The provider is `exported="true"` because `adb
   shell` is a different UID and cannot reach a private provider; `call()` then refuses
   every caller that is not the app itself, the shell (2000) or root. It is declared
   without a permission deliberately — a permission would need `pm grant` before every run,
   and the UID check is the gate that actually executes.

7. **No new MCP server and no duplicate domain tools.** The Android host serves the same
   JSON envelope as the CLI (`{"op":…}` in, `{"ok":…,"result"|"error":…}` out) and dispatches
   through `ScreenInspectorRuntime`. It is not an MCP server; it is the same contract behind
   a second transport. The `op` dispatch table is written out again in
   `ScreenInspectorHost` rather than shared with `ScreenInspectorCli`, deliberately: the CLI's
   table is bound to Clikt commands, its own process model and `ProgramResult` exit codes,
   and the two would grow together only by accident. What is shared is everything below the
   table — the runtime, the registry, the session and the envelope — which is where drift
   would actually cost something. Duplicating a `when` over eleven string keys is the
   cheaper side of that trade, and `ScreenInspectorHostContractTest` pins the host's side of
   it.

8. **Requests are serialised, and a replaced container fails closed.** One monitor covers
   both the dispatch and the teardown, so a request cannot be mid-flight while its runtime is
   closed — the failure mode being a request that reports failure for an action it already
   performed. The runtime captures the container it was built from, so a *different*
   container means the open sessions belong to a graph that no longer exists: that answers
   `internal` rather than silently swapping the runtime. On Android the container is created
   once per process, so this is a programming-error path, not a normal one.

9. **Caller-supplied waits are bounded.** `waitMs` and `timeoutMs` accept 0…120 000 ms. The
   provider answers on a binder thread, and `Long.MAX_VALUE` is legal JSON — enough such
   requests would park every binder thread the process has and make *unrelated* IPC in the
   app stop answering. A debug surface must not be able to do that.

10. **The lifecycle hook releases on the last activity *stopped*, tracked by identity.**
   `onActivityStarted`/`onActivityStopped` are a genuine pair; resume/stop are not (an
   activity pauses and resumes many times without stopping, and can stop without ever
   resuming), so a counter over them drifts — it either never releases the runtime or
   releases it too early. Pause is deliberately not the trigger: a permission dialog or a
   configuration change pauses without meaning the session is over.

11. **Every field is validated before anything is dispatched.** A request is read whole and
   rejected whole: an unknown `op`, a non-string `op`, a malformed body, a negative or
   non-numeric `waitMs`/`timeoutMs` — each answers `invalid_arguments` **before** a runtime
   is touched. This is stated as a decision because the first revision got it wrong for
   `waitMs`: it validated *after* `runtime.invoke`, so a request with `"waitMs": -1` performed
   a real domain action and then reported the request as refused. A client had no way to
   learn the write happened. A later revision still created the *runtime* before validating,
   which is the same mistake one level down: the runtime captured a container the refused
   request had no business initialising.

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
- A release APK has no provider, no transport entry point (`ScreenInspectorDeviceHost`) and no manifest entry — nothing that can reach the inspector. The contract classes are compiled in and inert (decision 5). The gate is a source
  set, so no launch argument, property or intent can turn it on.
- `jvmAndroidMain` now exists. It is not a general home for "code two platforms share" —
  `commonMain` is that. The declaration states the one argument that justifies it, and the
  next file that wants in has to make the same argument.
- An Android *instrumented* test that drives the provider is not added here: the contract
  tests run on the JVM against an injected container, and the device path is verified
  manually with `adb`. A Compose/instrumented test would be the next slice if the host grows
  behavior of its own.

## Review notes

An independent cross-model review of the first revision found two defects in it, both
corrected here, and both are recorded because the *shape* of each is the kind that recurs:

- **Validation ran after dispatch.** `waitMs` was read from the request *after*
  `runtime.invoke(...)` had already reached the coordinator. A request with a negative or
  non-numeric `waitMs` performed the action and then answered `invalid_arguments`. The rule
  this record now states as decision 8 exists because of it, and
  `ScreenInspectorHostContractTest` pins it with a container that throws if it is touched.
- **The release gate asserted a boundary it could not see.** Its hand-written list of
  release source sets omitted `shared/src/jvmAndroidMain`, and the transport entry point —
  a public `object` with a public `handle()` — sat in that directory, so an Android release
  build carried a callable host while the test reported the boundary as intact. The entry
  point moved to `androidApp/src/debug`, and the gate gained an assertion that its own list
  covers every `src/*Main` set the build declares. A hand-written enumeration needs a check
  on its coverage, or it is a comment with a green checkmark.

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
