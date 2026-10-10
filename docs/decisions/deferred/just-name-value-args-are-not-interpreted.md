---
title: "Just Name Value Args Are Not Interpreted"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** [#86](https://github.com/gazon1/sing/issues/86)

**Found in:** MR-6 follow-up, while adding the Maestro gate recipe.

`just <recipe> name=value` does **not** assign `value` to `name` in this
environment — the whole token arrives as the value. Reproduced on just 1.57.0
with a two-line recipe: `just p tags=agenda` echoes `tags=[tags=agenda]`, while
`just p agenda` echoes `tags=[agenda]`.

**Impact:** any recipe invoked with named arguments silently receives a
malformed value. It did not error — it produced a confusing downstream failure
("No flow files carry tag(s): tags=agenda"), which is the expensive kind.

**Do this first:** use positional arguments (`just gm agenda`), and check the
recipe's `[doc()]` text shows positional usage. If named arguments are wanted
later, verify with a probe recipe first rather than assuming.

**Reproduced 2026-10-05, in a recipe written to prevent exactly this.** `coverage-ratchet` gained an
environment flag and its own documentation said `just cr RERUN=1`. That form does not assign here:
the flag was silently absent, `just cr` ran, exited 0, and measured less than asked — the expensive
kind again, and on the one gate whose entire subject is whether a measurement measured anything.

The recipe now rejects a `NAME=value` argument outright (`exit 64`) with a message naming the
environment form. The general fix is still unbuilt: **this is a `just` behaviour, not a repository
one, so every recipe that documents a flag is exposed to it.** A guard that lives in one recipe
protects one recipe. A guard in the shared test module, or a convention that recipes read flags from
the environment with an explicit `case` on `{{args}}`, would protect all of them.

**Try next.** Grep the recipes for `=` in `[doc()]` strings and in comments — any that tell a reader
to type `just <recipe> NAME=value` is documenting a form that does not work here. That is a
mechanical sweep and a mechanical fix.

---
