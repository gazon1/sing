---
title: "Nav Display Debug Border Not Found"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** #445

**Found in:** MR-C (tech-debt batch). The plan proposed adding a red-border
debug overlay to `NavDisplay` when `entries.isEmpty()` as a diagnostic for
`desktop-nav-goBack-blank-screen`. Investigation showed no such modifier
exists in the codebase and no obvious place to add it that would survive
the blank-screen bug (the compose tree is empty at that point, so any
modifier on `NavDisplay` would not render either).

**Try next:** this item is closed as "not implementable as described". The
diagnostic approach should instead target the shell layer —
`DesktopShellNav3Root` or `DesktopShellNav3` — where a `LaunchedEffect` or
`remember` on `currentRoute` can be observed before the tree goes blank.
A visible diagnostic there (before the blank) would confirm whether the
route change itself is the trigger.

---
