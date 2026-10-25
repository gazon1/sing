---
title: "Maestro drops the selector from inputText, so a flow taps the field first"
date: 2026-10-25
status: accepted
tags: [testing, maestro]
---

# Maestro drops the selector from `inputText`, so a flow taps the field first

## Context

Three auth flows — `02-signin-wrong-password`, `03-signup-toggle`,
`06-signup-unconfirmed` — had been failing since the calibration of 2026-10-22, and the
failure was filed as debt against the app: `config/docs/maestro-debt.yaml` carries
`auth_error_text is visible` and `auth_confirmation_headline is visible` as *product*
debts, with the note "the E2E build's auth stub starts signed in, so the error text is
unreachable".

The note was wrong, and the debt was in the wrong place. The flows typed like this:

```yaml
- inputText:
    id: auth_email_input
    text: "test@example.com"
- inputText:
    id: auth_password_input
    text: "WrongPassword123!"
```

Maestro's command log for the run of 2026-10-25 says what the runner actually sent:

```
{"inputTextCommand": {"text": "test@example.com"}}
{"inputTextCommand": {"text": "WrongPassword123!"}}
```

**The selector is gone.** Maestro 2.10 takes the text and types it into the *focused*
field; a selector on the command is dropped without a word. So the second `inputText`
appended the password to the email field, which is what the screen hierarchy at the
failure shows:

```
[auth_email_input] EditText text='test@example.comWrongPassword123!'
[auth_password_input] EditText text=''
```

The form was never valid, the sign-in button never fired, and the assertion that the
error text was on the screen failed — for a reason that had nothing to do with the app,
the stub, or the session.

## Idea

A flow that wants to type into a field has to say so by tapping the field. The rest of
the tree already does that: 24 of the tree's `inputText` commands are preceded by a
`tapOn` with the same id. The three auth flows were the only ones that used the
`inputText`-with-a-selector form.

## Decision

**A flow taps the field, then types.** The form

```yaml
- inputText:
    id: auth_email_input
    text: "…"
```

is replaced by

```yaml
- tapOn:
    id: auth_email_input
- inputText: "…"
```

in every flow that used it — the auth flows, all twelve of them, not only the three
that failed.

**The selector gate refuses the old form.** `scripts/ci/validate-maestro-selectors.py`
now reports an `inputText` that carries a selector (`id`, `idRegex`, `text`, `textRegex`,
`label`). The rule is about the *shape* of the command, not about the id: the id may be
perfectly valid and still be ignored, so nothing else in that gate could say a word. The
rule has its own tests (`scripts/tests/test_maestro_input_text_selector.py`), including
the half that says the repository's own flows are clean — a gate that only its own tree
can pass is a gate nobody trusts.

## Rationale

The failure was silent in the worst way: the command was well-formed, the id was a real
id, the flow type-checked, and the selector gate had nothing to say. What gave it away
was the command log — the runner's own record of what it sent to Maestro — and the
screen hierarchy at the point of failure, which showed the text in the wrong field.

A gate for a silent no-op is worth more than the fix: the fix is three lines per flow,
and the gate is what stops the next flow from spending an afternoon on it.

## Consequences

- Flows 02, 03 and 06 pass on a device (RMX3938, API 35), and they pass because the
  flow drives the stub, not because the sign-in happened to succeed.
- The three auth causes in `config/docs/maestro-debt.yaml` that these flows carried are
  marked fixed.
- The `inputText` form is still accepted by Maestro — it is dropped, not rejected — so
  the gate is the only thing standing between a flow and a silent no-op.

## Links

- `scripts/ci/validate-maestro-selectors.py` — the rule and its error message.
- `scripts/tests/test_maestro_input_text_selector.py` — the rule's tests.
- `docs/decisions/2026-10-25-the-stub-is-driven-by-a-flag-file.md` — the channel the
  three flows drive, and why the property path it replaced was unproven.
