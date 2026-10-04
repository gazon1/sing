# usage-recording-platform-parity

Issue: #106 · Backlog entry: `usage-recording-text-gen-requires-cross-cutting-architecture`

## What

Bind `TextGenPort` through `UsageRecordingTextGen` on Android as well as on the
JVM, and add a check that holds the two source sets to the same contract.

## Why

`AiToolsModule.jvm.kt:105-118` binds `TextGenPort` wrapped in
`UsageRecordingTextGen`, with a `RoomUsageRecorder` and a locally-bound
`Clock.System`. `AiToolsModule.android.kt:102` binds `TextGenPort` straight to a
raw `KoogAgentService`.

The ADR's decision is therefore implemented on one platform and not the other.
Android LLM usage is never recorded, so `llm_usage` — the table the dogfooding
MCP server reads to know what its own agent calls cost — is empty for every
Android call, while the JVM rows make the feature look alive.

This is the platform-parity shape of defect. Each platform's own build is green,
nothing throws, and the missing data is only visible by comparing the two. The
symptom looks like a usage problem; the cause is one line of DI.

## How

Mirror the JVM binding in `androidMain`. The decorator already takes a recorder
and a clock, and both are platform-neutral, so the change should be the binding
and nothing else.

The durable part is the check. A DI test that resolves `TextGenPort` and asserts
the resolved instance is a `UsageRecordingTextGen` runs on both source sets and
catches a future platform added without it. Reading two files and comparing them
by eye does not survive the next platform.

One constraint worth recording before the check is written: this ADR's `core` →
`feature/ai` import direction is the only place in the repository where
feature-level types are referenced from `core/di`. A check placed in
`core/di` inherits that inversion. Put it where the inversion is acceptable, or
note explicitly why it is.
