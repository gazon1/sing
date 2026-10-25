---
title: "The auth suite is split: `auth` runs in the E2E build, `auth-live` needs a provider"
date: 2026-10-25
status: accepted
tags: [testing, maestro, auth]
---

# The auth suite is split: `auth` runs in the E2E build, `auth-live` needs a provider

## Context

`just gm <tag>` runs the flows that declare `<tag>`, and the runner selects a flow when
it declares **any** of the given tags. The `auth` tag collected twelve flows. Two of them
— `08-signup-network-error.yaml` and `09-signin-network-error.yaml` — cannot run in the
E2E build at all: they need a real provider to lose, and the E2E build replaces the auth
repository with a stub (ADR `2026-10-25-the-stub-is-driven-by-a-flag-file`), and they
need the server-configuration screen, which a build with a compiled-in Supabase project
never shows.

The tag said "this is an auth flow". The runner's use of it was "this flow belongs to the
suite the E2E build can run". Two meanings, one tag, and the runner picks by the second.

The `manual` tag on those two flows did not help: nothing reads it. It says "run me by
hand" to a human reading the file, and the runner — which selects by any tag — picked
them up anyway because they also declared `auth`. A tag that no code reads is a note in
a comment wearing a tag's clothes.

## Idea

The tag that selects a suite should say where the flow can run. A flow that needs a live
provider is not part of the E2E build's auth suite; it is a different suite, and it
belongs to a build that has a provider.

## Decision

**`auth-live` is the tag for auth flows that need the real provider.** Flows 08 and 09
carry `auth-live` instead of `auth`. They keep `manual`, which says the same thing to a
reader, and they keep their scenario tag.

They also drop `regression`. That tag is the classification of the automated suites
("P0 + P1, full feature coverage"), and a flow that no automated suite can run is not
one of them. Leaving it would have kept the footgun: `just gm regression` would still
have collected two flows that cannot pass.

Flow 10 (`auth-attach-account-validation-error`) stays in `auth`. It was in the same
suspicion — "the stub always succeeds, so the failure axis is unreachable" — but the
assertion it ends on is a *client-side* one: a password shorter than
`AuthDomain.MIN_PASSWORD_LENGTH` is refused by the app before any provider is asked.
The stub's success is irrelevant to it.

## Rationale

A green run has to mean something. A suite that contains flows which cannot pass in the
build that runs them reports a red that says nothing about the product, and the habit it
builds is to read red as "known" — which is exactly how the auth causes in
`config/docs/maestro-debt.yaml` were accepted as debt rather than traced to a broken
test channel.

Splitting by tag rather than by directory keeps the runner's rule intact: the tag is the
selection, wherever the flow lives.

## Consequences

- `just gm auth` selects the ten flows the E2E build can run. `just gm auth-live`
  selects the two that need a provider.
- The nightly `full` suite is unchanged: it selects every flow in
  `config/docs/maestro-flows.txt`, and that catalogue lists paths, not tags. The two
  flows still run there and still fail there; the debt floor still counts them. What
  changed is that the failure is no longer *in the automated auth suite*.
- `Maestro/README.md` carries the tag table, and the table now says what `auth-live`
  means.

## Links

- `Maestro/README.md` — the flow-tag table.
- `docs/decisions/2026-10-25-the-stub-is-driven-by-a-flag-file.md` — what the E2E build
  can and cannot show.
- `config/docs/maestro-debt.yaml` — the causes this change traces back to their cause.
