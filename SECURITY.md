# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. A public issue
tells everyone about the flaw before a fix exists, and most of what this
application handles — task text, notes, project names, and any credentials it
stores — is private by definition.

Report privately through GitHub's coordinated disclosure on this repository:
**Security → Report a vulnerability**. That opens a private advisory visible
only to the maintainers.

Please include, as far as you can:

- what an attacker can do, and what they need in order to do it;
- the affected file, class, or screen;
- the version, commit SHA, or build you tested;
- a reproduction, if you have one.

You should get an acknowledgement within a few days. Please give the maintainers
a reasonable window to publish a fix before disclosing publicly. If a report
turns out not to be a vulnerability, that is not a penalty — no blame, and you
will be told why.

## What this application handles

Stated plainly, because a security policy that lists nothing is not a policy:

- **Task and note content** — free text the user wrote, including attachments.
- **Credentials** — a Supabase session, held through `SecureStoragePort`
  (libsecret on desktop, the platform keystore on Android). It is not in
  `SharedPreferences` and not in the database.
- **Sync payloads** — the same content, sent to a Supabase instance the user
  configures.
- **AI requests** — note and task text sent to whatever LLM provider the user
  configures, including their API key.

Everything here is local-first: without a configured sync backend and without a
configured AI provider, nothing leaves the device.

## Threat model in one paragraph

The interesting attacks are not remote code execution — this is a local client —
but **sync-borne**: a malicious or compromised server sending a patch that
rewrites a row the user owns, and the client applying it without asking. The
sync layer's answer is a Hybrid Logical Clock plus a base-version check, so a
patch built against a stale state is refused rather than applied. That machinery
is load-bearing for the user's data and a good place to look if you are doing
adversarial testing. The outbox, the dead-letter table, and the shadow record of
what the server is known to hold are all reachable from
`shared/src/commonMain/kotlin/com/singularity/todo/core/sync/`.

## Supported versions

The project is pre-1.0 (`versionName 0.1.0`) and has no tagged releases. Fixes
land on `main`. There is no long-term-support branch to name, and this section
will gain one if that changes.

## What is out of scope

- Vulnerabilities in Supabase, JetBrains Koog, or any third-party dependency.
  Report those upstream; we will upgrade.
- Findings that require a user to have already handed over their own credentials
  or run attacker-supplied code.
- The absence of features. An unwired capability is a product decision, not a
  vulnerability, and `docs/decisions/deferred-backlog.md` records what is known
  to be missing.
