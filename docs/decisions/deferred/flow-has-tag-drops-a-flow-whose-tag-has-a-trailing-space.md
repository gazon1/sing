---
title: "Flow Has Tag Drops A Flow Whose Tag Has A Trailing Space"
date: 2000-01-01
status: CLOSED.
tags: ["deferred"]
---

**Status: CLOSED.** 2026-10-05.

**Tracked as:** [#148](https://github.com/gazon1/sing/issues/148)

**Found in:** the gate audit in
`2026-10-05-gate-audit-text-shape-vs-fact` (0A.5), which asked every test gate
"what input passes silently?". Reproduced by probe the same day, not inferred.

**Symptom:** `flow_has_tag` (`scripts/run-maestro.sh:166`) compares a flow's
header tag to the requested tag with awk string equality. A tag with one
trailing space does not match, so `TAGS=smoke scripts/run-maestro.sh` omits that
flow and says nothing. Nothing asserts that every flow is reachable by some tag,
so a flow dropped this way disappears from every suite while every gate stays
green — the same shape as the D1 defect, where two classes were reported clean
by the gate that exists to report them.

**Already ruled out:** not a Maestro behaviour. `--include-tags` is ignored when
a single file is passed, which is exactly why the filter is applied in the
script; the comparison is the script's own.

**Fix, and why the first deferral was the wrong call.** The tag matching moved
out of `run-maestro.sh` into `scripts/maestro-flow-tags.sh`, a sourceable file
with no adb or emulator dependency, and both ends of the comparison are now
trimmed. This was filed rather than fixed alongside the other gate repairs
because the change could not be exercised in that environment — which turned out
to be the wrong reason. The matching is pure text and is now unit tested
(`scripts/tests/test_maestro_flow_tags.py`, 16 cases) with no device at all.

Three further defects surfaced while writing those tests, none visible before:
the tag block was never terminated, so a step like `- tapOn: 'x'` under
`commands:` was read as a tag and `TAGS=tapOn:` would have selected every flow;
`tags:  # comment` did not open the block; and a CRLF-edited flow kept a `\r`
in its tag and matched nothing.

The tests add the invariant that closes the loop and which nothing in CI
asserted before: every flow declares at least one tag, every declared tag
selects its own flow, and every `TAGS=` value CI asks for resolves to at least
one flow. Measured over the current tree: 58 flows, none untagged, no
unreachable tag, `TAGS=smoke` selects 19.

---
