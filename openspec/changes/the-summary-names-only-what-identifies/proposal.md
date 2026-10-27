# The summary names only what identifies

Issue: — · Related: FR-020, T062, `openspec/changes/the-turn-is-one-model-call`

## Why

FR-020 says a tool-call summary must contain only identifying information and must not
contain the user's content. The code could not tell the two apart: the rule was "quote a
value if it is single-line and at most 60 characters", and length says nothing about
whether a string is an identifier or a note body.

So a one-line note body reached the card. `update_note` with `{"bodyMarkdown":"Buy milk and
eggs"}` was summarised as `bodyMarkdown=Buy milk and eggs` — the exact content the summary
exists to keep off a screen that was about to be shared, photographed or screenshotted. The
specification recorded the gap rather than hiding it (FR-020's assumptions, T062).

## What Changes

- The rule becomes **field-based**. A value is quoted only when its field is one that
  identifies — an identifier, a title or name, a kind or state, a date, a number or a flag —
  and the value is single-line and at most 60 characters. Every other string is named and
  elided (`bodyMarkdown=…`), however short it is. Numbers and flags need no field entry: a
  number or a boolean cannot carry a paragraph.
- The set of identifying fields is **explicit**, in one place, so a new free-text argument
  cannot leak by being short — it has to be added where a reviewer sees it, with the reason.
- FR-020's wording is amended to say what the rule is: the field decides, not the length.
- The tests assert the gap directly (a short free-text value is elided) and keep the
  existing bounds.

## Impact

- Affected specs: FR-020 in `specs/014-acp-client-agent-activity/spec.md`, amended — the
  requirement's intent is unchanged, its measurable part now matches it.
- Affected code: `core/llm/ToolCallSummary.kt` and its tests. The rule is one function, used
  by both the built-in agent's loop and the external agent's adapter, so both cards change
  together.
- What is explicitly **out of scope**: the 200-character bound and the field count (FR-019,
  FR-020) are unchanged; the raw-payload reveal (FR-021) is unchanged; which fields a tool
  accepts is unchanged — only which of them a card may quote.
- Supersedes nothing. Closes T062.
