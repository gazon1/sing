---
title: "No Direct Dispatchers Rule One Whitelisted Case"
date: 2000-01-01
status: OPEN.
tags: ["deferred"]
---

**Tracked as:** #444

**Found in:** MR-B (tech-debt batch). `NoDirectDispatchersRule` bans
`Dispatchers.IO/Default/Main` in production. One legitimate case was
identified: `core/log/FileLogWriter.kt:50` uses
`Dispatchers.IO.limitedParallelism(1)` to guarantee sequential writes.

**Status: OPEN.** Corrected 2026-10-05: the whitelisting itself is in the rule
code. The rule had no `detekt.yml` block at all, so it never ran; a block was
added that day (`no-direct-dispatchers` / `NoDirectDispatchers`, `active: true`).
What remains open is the sweep in #44, not the whitelist.

**Note:** the whitelisting is already done in the rule code
(`isAllowedFile` for `FileLogWriter.kt`). The rule is `active: false`
pending the sweep of any other callers. If no other callers exist, the
rule can stay `active: false` indefinitely — the whitelist is the fix,
not a signal to search for more cases.

**But "0 findings" proved nothing, and this entry previously claimed it proved
something.** The rule could not fire for any input: it required the dot-qualified
selector to be a `KtCallExpression`, but in `Dispatchers.IO` the selector is a
`KtNameReferenceExpression` (`IO` is a property), and in
`Dispatchers.IO.limitedParallelism(1)` the receiver is itself dot-qualified. Both shapes
returned early. It was a registered, packaged, ADR-referenced no-op.

Fixed the same day, together with the scope. The rule is now scoped to **commonMain
production** only, which is what its KDoc always claimed: verified against the tree,
commonMain has exactly one occurrence (`FileLogWriter.kt:50`, the whitelisted line),
while jvmMain has 8 and androidMain has 11 — all inside port implementations, where
choosing the dispatcher is the KMP convention rather than a violation. With the rule
actually working, `:shared:detekt` reports 0 findings, and *this time that means
something*: it was verified by 17 tests, not inferred from a silent rule.

**Try next:** if a new legitimate commonMain call site appears, add it to
`ALLOWED_FILE` in `NoDirectDispatchersPolicy` rather than disabling the rule.

---
