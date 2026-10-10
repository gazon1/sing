---
title: "Two Line Length Authorities Detekt Default 120 Beats Editorconfig 140"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-04, while reformatting the lines the `runCatching` →
`runCatchingCancellable` migration pushed over the limit.

**Status: OPEN**

**Tracked as:** #454

**Symptom:** `.editorconfig` sets `max_line_length = 140`, and `detekt.yml`
carries the comment "ktlint owns line length via .editorconfig". But ktlint's
`max-line-length` rule is `active: false`, and detekt's own
`style:MaximumLineLength` is **not configured at all** — so it runs on detekt's
built-in default of **120**. The stricter value silently wins while the config
says the project allows 140.

**Already ruled out:** not a stale report. It reproduces from clean, and lines of
121–131 characters are the only ones rejected.

**Deliberately not fixed here.** The 2026-10-04 migration rewrapped its 16
affected lines to 120 rather than relaxing the gate: bundling a gate-relaxation
decision into a correctness fix means the correctness fix cannot be reviewed
separately, and "the limit was wrong" is exactly the claim that has to be
argued rather than assumed.

**Try next — pick one and make it true:**

1. **Keep 120.** Then `.editorconfig` should say 140 → 120, and the detekt.yml
   comment should be corrected. The stricter limit is already the de-facto house
   style, and lowering a documented number to match observed practice is a
   one-line change.
2. **Keep 140.** Then add `style: MaximumLineLength: maxLineLength: 140` to
   `detekt.yml` explicitly. This *relaxes* an active gate, so it needs a reason
   recorded here and ideally a `LongMethod`-style justification.

Option 1 is the lower-risk of the two: it removes a false claim rather than
loosening a real constraint. Whichever is chosen, the other file has to change
too — leaving the mismatch in place is what produced the confusion.


---
