# Quickstart: Validating This Feature End to End

How to prove each part works, in the order that fails fastest. Every check below is a runnable command or an observable outcome — none of them needs implementation knowledge.

## Prerequisites

- A working development environment (the project's standard setup).
- For the manual run at the end: one external agent installed and reachable — the Gemini CLI in its ACP mode is the default entry.

## Ground rules

- Build with `./gw`, not `./gradlew` — parallel checkouts share a Gradle home and the shared one serialises on a lock.
- Never run two Gradle invocations at once, in this checkout or any other.
- Run detekt for `:shared` first; it holds most of the code and fails fastest.
- Every test class carries a tag; a class without one is silently excluded from CI. New classes follow the tag dictionary.

---

## Part A — Data safety

### A1. The probe does not delete on an ambiguous result

Each case runs the startup probe against a prepared file and asserts the file is **byte-identical** afterwards — content, not merely presence.

| Prepared file | Expected |
|---|---|
| locked by another process | unchanged |
| unreadable / no permission | unchanged |
| corrupted database | unchanged |
| empty file | unchanged |
| valid, no application bookkeeping marker | deleted, together with its companion files |
| valid application database | untouched |

Failing this test is the whole point of Part A: today the probe deletes the file in every one of the ambiguous cases.

### A2. A second process does not hijack the active profile

Start the tool server against a named profile, work through it, exit. Then assert:

- the application's stored active profile is unchanged;
- the preferences store was never opened by that process (asserted by observing that no lock conflict occurs);
- an unknown profile id exits non-zero with a diagnostic naming the id;
- passing both selectors exits non-zero;
- the legacy label still behaves as before, for both documented values.

### A3. Errors are valid and leak nothing

Send a request whose failure carries a quote, a newline and a control character. The response must parse as a protocol message and must contain no file path and no internal message. Repeat for a database-startup failure.

### A4. Every tool says how it writes

- The classification check fails when a tool has no classification.
- It fails when a direct-write exception carries no reason.
- Contract test, for a proposal-gated tool: a call produces a proposal; confirming writes; **declining leaves the data unchanged**.
- After adding the registry field, regenerate the exported tool surface in the same change and confirm its gate is green.

---

## Part B — Activity visibility

### B1. A turn shows its work

Drive the built-in chat with a prompt that triggers several tool calls, one of which fails. Observe:

- one card per call, with the tool name and a running → completed status;
- the failed call shows as failed rather than disappearing;
- the plan appears with per-step state;
- the turn ends.

### B2. Every turn reaches an outcome

| Situation | Expected outcome |
|---|---|
| normal answer | completed |
| a tool fails | the turn still ends — completed, with the failure visible |
| the user cancels | cancelled; the partial answer stays; nothing spins |
| the stream ends abnormally | failed — never "completed" |
| the answer is cut short by a limit | completed, with a truncation note |

### B3. Summaries are safe

A call whose payload contains note bodies shows only a bounded summary; the raw detail is hidden until asked for; and nothing raw reaches the activity log.

### B4. The screen is not the only consumer

The same card renders in the built-in chat and, in Part C, on the external-agent screen. If the two diverge, the shared renderer has been bypassed.

---

## Part C — External agent

### C1. The delivery probe runs first

Build a packaged desktop distribution and run the repository probe. It reports: what the runtime classpath contains at run time, whether the tool server's main class starts from it, and where the application jar lives. **Record the result in the ADR before implementing a delivery mechanism** — the choice follows the probe, not the other way round.

### C2. Connection lifecycle

Against a fake agent process:

- connecting, sending, streaming, cancelling, closing;
- a turn that completes normally;
- an agent that dies mid-turn: the partial answer stays, an error with a reconnect is shown, and **nothing is retried**;
- an agent that hangs: closing the session kills it;
- an agent that is not installed: a diagnosable error naming the command and where it was expected — never a stack trace;
- a version mismatch between the bundled tool server and the running one: a warning.

### C3. Permissions

- accept, decline;
- no answer within the timeout → declined, and the turn continues;
- cancel the turn while a request is outstanding → the request resolves, nothing dangles;
- close the session with a request outstanding → same.

### C4. No access to your tasks

Make the tool server unavailable, then connect. Expect: the session still connects, the warning appears **before the first message**, it stays for the whole session, and it is visibly different from a tool that failed after a successful connection.

### C5. The process tree

Run a fake agent that itself starts a child process. End the session. Assert that **no** process from that tree remains — the child, the agent, anything else. This is the test that catches an orphaned tool server, and it is the one that fails if only the root process is killed.

### C6. Against a real agent, in a packaged build

One manual pass with a real agent: connect, ask for a read, ask for a change, confirm the proposal appears and applies only after confirmation, cancel a turn, close, and confirm nothing is left running. Automated tests use fakes precisely because real agents are not available everywhere.

---

## Cross-cutting gates

Run before every push; the pre-push hook runs the structural ones and the linter for you.

```bash
./gw :shared:detekt
./gw :desktopApp:detekt
scripts/find-unwired-surfaces.py        # the new screen must have a real call site
./gw :shared:jvmTest                    # fast cycle
./gw :shared:jvmTest -Ptest.tags=fast,slow   # the full set, as CI runs it
./gw :shared:exportToolSurface          # only after a registry change
bash scripts/ci/static-gates.sh
just tests::impact                      # which tests this change can break
```

Two gates catch this feature specifically:

- **Unwired surfaces** — a screen with no call site is a blocking finding. The external-agent screen needs a real navigation entry from the JVM-side navigation list.
- **Subprocess confinement** — process spawning must go through the one allowed file. Adding a duplex spawn there is fine; spawning anywhere else fails the architecture test.

## Definition of done per part

| Part | Done when |
|---|---|
| A | The probe never deletes on an ambiguous result; a second process cannot change the active profile; every tool is classified and every direct write has a reason; error responses are always valid and never leak internals. |
| B | The chat shows every call, its outcome, and the plan; every turn reaches one outcome including on cancel and on stream loss; the renderer is shared. |
| C | One agent connects from a packaged build, works on the user's tasks, asks before writing, survives a crash without duplicating anything, and leaves no process behind. |