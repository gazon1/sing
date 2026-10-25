# the-genui-count-travels-with-the-value

Issue: — · Related: `docs/decisions/2026-10-21-the-genui-count-travels-with-the-value.md`

Status: **accepted**
Tags: **genui data model rewrite count surface field**

## What

A field bound to a path in a GenUI surface needs to know whether the value at that path
was replaced from outside. The count that says so was kept in the surface controller, in a
map beside the surfaces — one entry per surface, holding the number of the server's
writes to *any* path of that surface — and the context assembled the pair itself, reading
the value from the data model and the count from the controller.

Now the count travels with the value it counts:

- the count lives in the surface's data model, **per path**;
- the document and the counts are one state, so a reader takes both in one read and a
  subscriber gets both in one emission;
- the field's own write leaves the count where it is; the server's write moves it;
- the controller holds no count, and the context reads no count apart from the value.

## Why

The count was not about the value. A server write to one path moved the count for every
path of the surface, so a field bound to an untouched path was told it had been rewritten
from outside — the bridge lifted its echo guard, and a stale value was applied over what
the user had typed. The guard exists to stop exactly that.

The count also outlived the surface: closing or pruning a surface left its count behind,
and so did a reset, so a surface that came back under the same id inherited a count that
described a surface that no longer existed.

And the pair could be torn: the value and the count came from two stores, read at two
moments.

## The shape of the fix

- The data model holds the document and the counts in one state.
- Two ways to write: the field's own, and the one that comes from outside and moves the
  count.
- The count is per path.
- The context reads the value and the count together and turns them into the field's
  source.

## What not to do

- Do not keep a counter beside the data. It is a second store for a fact about the data,
  and the two can disagree.
- Do not make the count per surface. The question the field asks is "was *my* value
  replaced?", not "was anything on this surface replaced?".
- Do not read the value and the count separately and glue them together. The pair is what
  the bridge compares.
