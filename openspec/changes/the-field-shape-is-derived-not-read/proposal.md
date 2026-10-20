# the-field-shape-is-derived-not-read

Issue: — · Related: `docs/decisions/2026-10-21-the-field-shape-is-derived-not-read.md`

Status: **accepted**
Tags: **text field shape derivation imports**

## What

The **shape** of a text field — *local*, *bridged*, *anchored* — is derived from the
code's **imports**, not read out of the code's text.

`TextFieldShapeConventionTest` used to walk the tree and ask whether a file's *source
text* contained `rememberTextFieldState(`, `rememberBridgedTextFieldState(` or
`rememberAnchoredTextFieldState(`. That is the trick the rewrite contract had just
removed: a text scan cannot tell a call from a mention, so a file's own documentation
was evidence about the file's behaviour.

A file that uses the bridged helper has to **import** it, because the helper lives in
`com.singularity.todo.core.ui.textfields`. The import list is the compiler's own record
of the shape.

## Why

The helpers used to live in `com.singularity.todo.core.ui.components`, next to the
fields that used them. A file in that package could call the helper with no import at
all, so an import-based derivation would have missed it — the same-package hole. The
helpers move to their own package first, and the package holds no field sites, so the
helpers are excluded from the scan by their *path* rather than by a list of file names
that would rot.

The rewrite contract's own scans had the same flaw in a milder form: the owner's source
was found with a regex over the file's text, the counter by searching the text for the
word "revision", and the bridge's parameters out of a regex over its declarations. They
are now read from the declarations themselves — a property with a type, a property with
a name, a list of parameter names.

## The shape of the fix

- The helpers move to `com.singularity.todo.core.ui.textfields`, and the API is
  unchanged.
- `TextFieldShapeScan` reads every file's imports and derives the shape. A file that
  imports the bridged helper *is* a bridged site.
- The registry in the shape convention test stays — writing the classification down is
  the point — and is compared with the derived shape.
- The rewrite contract's sites come from the same derivation. The owner's source is a
  property with a type, the counter is a property with a name, the bridge's parameters
  are names.
- The two checks Konsist 0.17.3 cannot express — a counter that *moves*, a site that
  *constructs* a source — are read from the **declarations** of the file, with the
  comments stripped, and a preview is excluded by its annotation rather than by the word.

## What not to do

- Do not put the helpers back next to the fields. The import is the evidence, and a
  same-package call has none.
- Do not go back to scanning the source text. A mention is not a use.
- Do not derive the registry. A field nobody classified has not been classified, and a
  derived registry would classify it the moment it was written.
- Do not let the derivation grow an exception list. The helpers' package is the only
  exclusion, and it holds no fields.
