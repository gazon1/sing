---
change: agent-surface-follow-ups
verdict: pass
date: 2026-10-23
pr: https://github.com/gazon1/sing/pull/1041
reviewed: 966f1740bf1a6e36707a8e1b52f7f8f1cf272851
documents: sha256:8ceab0ab6e40ce5dccd6e049d415c127b9407ac00038efc754f3036f3c26d1fb
---

## What was reviewed

This change closes the five items the review of the external-agent feature left
behind. The review itself is not repeated here; what is reviewed is the work
that answers it.

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`
  and `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolSurfaceExport.kt`
  — the classification and its publication (1.1)
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt`
  — the client's lifecycle (1.3)
- `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ProtocolStdout.kt` and
  `shared/src/jvmMain/kotlin/com/singularity/todo/core/log/StderrLogWriter.kt`
  — the protocol's output (1.4)
- `mcp-server/build.gradle.kts` — the module's rules (2.2)
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/` and
  `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerStartup.kt`
  — the two god classes (2.3)

The review that named the five items was a second agent's, given the diff and
the decision record rather than the session that wrote the feature. The work
was split between that session and three workers, and every worker's output
was read and checked against the item it was given rather than taken on
trust.

## What was found, and what was done about it

**The catalogue was stale, and the review had already guessed it.** `create_tag`
was declared a direct writer while the tool had been proposing for a while —
the declaration was the stale half of a real drift, and nothing noticed. It is
reclassified, and the export now carries the behaviour and the reason, so the
declaration is readable from outside the compiler.

**A close that landed during a turn broke the goodbye.** The lock and the fast
close were implemented together, and the fast close closed the transport before
the goodbye was sent — so the goodbye went out on a closed channel and a reader
who pressed Disconnect was met with an exception instead of a closed screen.
This was not in the review; it was found by the tests this change added, and it
is the reason the transport now goes only when there is nothing to say on it.

**A close that landed during a connect waited for the handshake.** The lock
alone would have made a close wait out a handshake that may take a minute,
because the agent may be an `npx` invocation downloading its package on first
use. The close now ends the handshake before it waits for the lock.

**The tag-coverage gate read a class as untagged because of a comment.** The
explanation for the suppressed `runBlocking` in the end-to-end test sat between
the `@Tag` lines and the class header, and the gate reads the *contiguous*
annotation block. An untagged class is one that no CI population ever selects,
so the finding was real: the test would have stopped running.

**The full detekt configuration surfaced twenty findings in the new code**, all
fixed rather than baselined: four class signatures that fit on one line, two
loops with more than one jump, a generic catch, a missing trailing comma, a
blank line between `when` conditions, a blank line before a closing brace, and
a mangled line in one of the contract tests.

## What is still open

- The ACP client against a real bridge — issue #993.
- The god-class debt beyond the two classes named here — issue #994.
- The archive order. `ai/write-classification` is introduced by the still-active
  `acp-client-data-safety` change, so this delta modifies requirements that are
  not in `openspec/specs/` yet: `acp-client-data-safety` archives first, this
  one after. The delta validates, and the archive would refuse it until then.
- The two exceptions the two-sided check cannot verify — `write_adr` and
  `update_adr` — are named in the test rather than failed, because they write
  through a storage port the repository scan cannot see. A gate that cannot see
  the write would be a gate that lies.

## How the fixes were checked

- `:shared:jvmTest :mcp-server:test :desktopApp:test` with the full population —
  every test, the slow ones included.
- `:shared:detekt :mcp-server:detekt :desktopApp:detekt :androidApp:detekt` —
  no findings, and no baseline entries added.
- The two races are tested against a real agent on the other end of the pipe,
  not against a fake of one: a close that lands during a turn, and a close that
  lands during a connect.

## Links

- `docs/decisions/2026-10-23-the-agent-surface-says-what-it-does.md`
- `openspec/changes/agent-surface-follow-ups/`
