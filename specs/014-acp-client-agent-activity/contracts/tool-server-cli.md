# Contract: The Tool Server Command Line

How an external process asks the tool server for the user's data — and, just as importantly, how it says *no*.

## Why an explicit profile selector exists

The tool server currently derives its profile from a **label** (`--profile`). In practice only two labels do anything, an agent label switches the stored active profile, and any other value is silently ignored. That last part is the problem: a second process asking for a profile it cannot have is told nothing and carries on.

The application's active profile lives in a preferences store that takes a file lock. A second process opening the same store conflicts with the application. So the new mode must not touch that store at all — it works against a profile id given on the command line and leaves the application's stored state untouched.

## The arguments

| Argument | Meaning | Failure behaviour |
|---|---|---|
| `--profile-id=ID` | Work as this profile id. Do **not** open the stored active-profile state, do not switch anything. | Missing or unknown id → clear diagnostic, non-zero exit. Never a fallback. |
| `--profile=NAME` | Legacy label semantics, unchanged: the agent label activates that profile; any other label leaves things as they are. | Unknown label → warn, do nothing (current behaviour, preserved). |
| both | — | Startup error: the request is ambiguous. |
| neither | Legacy behaviour: the profile comes from the stored state. | — |

**The legacy argument is preserved deliberately.** Distributed client manifests already pass `--profile=ai-agent`; changing its meaning silently would break every one of them. Its use is deprecated in documentation and logged at startup (FR-008).

## What the new mode binds

In `--profile-id` mode the tool server resolves profiles through a fixed-profile repository instead of the stored one. Because dependency bindings are lazy, the preferences store is never constructed, so its file lock is never taken — that is the mechanism, and it is asserted by a test rather than by argument.

The binding is extracted into its own module and composed into the domain module list. **It is added to the list, never `includes()`d**: the module aggregator comment records that a previous attempt to wrap these bindings produced a child scope where sibling modules could not see them.

## Exit behaviour

| Condition | Result |
|---|---|
| Valid profile id | Serves tools for that profile; the application's stored active profile is unchanged. |
| Unknown profile id | Diagnostic naming the id and what was expected; non-zero exit. |
| Both selectors | Diagnostic naming the ambiguity; non-zero exit. |
| Database or startup failure | Diagnostic with **no internal detail** in the protocol stream; details to the log; no half-started process left behind. |

## Error responses

Errors are serialised by the serialiser, never assembled by string interpolation. The current builder interpolates the message into a JSON template by hand, so a quote or a newline in an error text produces a malformed response — and the text it interpolates carries internal detail such as file paths and database messages.

The contract: **the response is always a valid protocol message, and it never contains internal diagnostics.** The caller gets a neutral message; the operator gets the detail in the log.

## The launch command handed to the agent

The app publishes a stdio server reference: a name, a command, an argument list, and an environment. stdio is used because every agent must support it, while HTTP and SSE require capabilities agents may not declare.

The command runs the JRE **shipped with the application**, never a system `java` — packaged users have no system Java, and a version mismatch with the shipped runtime is a defect class that only appears after packaging.

## If the command cannot be produced

The session starts **without** tool access and shows a warning before the first message, and that warning stays for the whole session. This is deliberately not a connect failure: the connection is fine, the agent simply has no access to the user's tasks, and the two states are shown differently (FR-032, FR-033).

## Version agreement

The application compares the version it expects with the version the running server reports in its handshake — the same value already flows into both artifacts from one build property. A mismatch is a warning, not a failure (FR-043).

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Repurpose `--profile` to take ids | Silently breaks every distributed manifest. |
| Fall back to a default profile on an unknown id | A second process would quietly read the wrong user's data — the exact class of bug this selector exists to remove. |
| Open the preferences store read-only | The lock is taken regardless of the mode of access. |
| Let the agent choose the profile | The user picks; the app passes it. |