---
title: "Maestro flow qualification — the references are read from the document"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, fingerprint]
issue: 3052
---

## Context

The gate fingerprints a flow as the flow file plus every file it runs, and it
finds those files by scanning the flow's *lines* with three regular expressions:
`file: path`, `- runFlow: path`, `- runScript: path`.

A line scan does not know where a value ends in YAML. The patterns required the
reference to be the last thing on the line, so a trailing comment —
`file: ../../helpers/seed.yaml # shared setup`, which is valid YAML and which
Maestro resolves — left the reference invisible. The gate then did not validate
it, and did not include it in the fingerprint: the helper changed, the
fingerprint did not, and the changed helper stayed in a blocking scope without
anyone qualifying it again.

The scan also could not see a flow named instead of pathed (`runFlow: SubFlow`),
which Maestro resolves to `SubFlow.yaml` next to the flow that names it.

The review round found this as a major defect in the central guarantee: the
transitive fingerprint closure had a hole in it.

## Idea

1. **Keep the line scan and teach it about comments.** Cheap, but it patches one
   shape of YAML and leaves the next one open: the scan still cannot tell a
   value from a line, and there are more shapes than comments.
2. **Read the flow as YAML and take the references from the document.** The
   document knows where a value ends; the scanner asks the document instead of
   guessing.
3. **Parse with a full YAML parser and drop the text scan entirely.** Loses the
   fallback for a document that is not valid YAML.

## Decision

**Option 2, with option 3's fallback kept as the fallback.**

- A `.yaml`/`.yml` file is read with `yaml.safe_load_all` — a Maestro flow uses
  `---` document separators, so it is more than one document — and the
  references are collected from the parsed structure: every `file:` value, every
  `runScript:` value, every `runFlow:` value. A `runFlow` whose value carries
  no extension is a flow *name* and resolves to `<name>.yaml` next to the
  referencing file.
- A file that is not YAML (a helper script) is scanned by line, as it always was.
- A document YAML cannot parse falls back to the line scan, now tolerant of a
  trailing comment. The gate still says what it found; a flow Maestro cannot
  parse cannot run either.

## Rationale

- **The scanner asks the document, not the line.** A comment, a nested form, a
  quoted value and a named flow are all ordinary YAML. Reading the document
  makes them ordinary instead of special cases.
- **A missed reference is not a cosmetic defect.** It is a dependency the
  fingerprint does not cover — the exact thing the rule exists to prevent.
- **The fallback keeps the failure mode honest.** A flow YAML cannot parse is
  refused by the attempts themselves; the gate still reports the references it
  found rather than reporting none.
- **The fingerprints did not move.** The 127 flows in the blocking scopes resolve
  to the same dependency sets under the new scanner, so the baseline and its pin
  are untouched: the change closes a hole, it does not re-admit anything.

## Consequences

- `flow_refs()` depends on a YAML parser. The repository already uses PyYAML in
  the gate's sibling scripts, and the gate keeps working without it (line scan).
- A flow that references a non-flow file (an image a capture step reads) is not
  a dependency, exactly as before — only flow and script files are.
- A named flow that does not exist next to the referencing flow is reported as a
  reference that is not a file inside the tree, instead of being ignored.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-tree-boundary.md`
