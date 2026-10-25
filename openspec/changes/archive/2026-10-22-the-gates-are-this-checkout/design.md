# Design — the gates are this checkout

The rationale for this change lives in
`docs/decisions/2026-10-22-the-gates-are-this-checkout.md`. This file records the
shape of the implementation and what would have to be undone.

## The shape

Four pieces, in four places.

### The origin of the tooling

The gate CLI is an editable-installed package: `sgate` lives in `scripts/src/sgate`
and is installed into a virtualenv. The install points at one checkout, so on a
multi-worktree host the import resolves to whichever checkout installed last.

Three seams fix it, and each is a seam where the resolution already happens:

- `scripts/check-gate-registries.py` — the script puts this checkout's
  `scripts/src` on the path *before* it imports the gate tooling. The order is
  the fix: the import used to happen first, so the script checked the other
  checkout's registries.
- `scripts/ci/static-gates.sh` — every python gate the shell registry runs gets
  this checkout's `scripts/src` on the path, so a gate that imports the tooling
  resolves it the same way whether it is run by the registry or by hand.
- `scripts/ci/preflight-gate-tools.sh` — the check is no longer "is the tooling
  importable?" but "is the tooling *this* checkout's?", and when it is not, the
  report names the path it resolved to.

The supported way to satisfy the check on a multi-worktree host is a
checkout-local environment (`SINGULARITY_TOOLS_VENV`), so the editable install
points at the checkout being verified.

### The catalogue's currency

`shared/build.gradle.kts` gains a verification task. It exports the catalogue to a
scratch file under the build directory and compares it with the committed
`config/docs/tool-surface.json`; a mismatch fails with the command that
regenerates it.

A separate task rather than a flag on the export: the export *writes* the file,
so it cannot report that the file is wrong — it would simply overwrite it. The
verification has to compare, and comparison is the thing that fails.

`check.sh` runs it as a step before the long test task, so a stale catalogue is
a fast failure instead of a twenty-minute one.

### The message at the place it broke

`scripts/render-adr-tools-section.py` reads a description constant out of the
source. A description written as a raw string is a form it cannot parse, and the
reader now says so — naming the form — instead of the difference surfacing
downstream as "the gate is reading the source wrongly".

### The write path

`scripts/check-ai-write-tools.py` scans the tool classes for calls on the user's
own repositories and compares what it finds with `config/docs/ai-direct-writes.txt`.

The registry is a **data file**, not a constant in the script, for two reasons
that point the same way: a registry nobody can read without running a program is
a registry nobody reads, and only a committed file can be mutated to prove the
gate can fail. The convention is the same one the other registries follow — a
registry is checked, not written.

The gate measures the state and does not decide it. Whether a given tool *should*
write directly is a decision that belongs to the change that converts the tool;
the gate only asks whether the state is what we said it was. That is why it
fails in three directions: an unlisted writer, a stale entry, and a tool that
both proposes and writes directly.

## The registry of gates stays the single source of truth

The new gate is registered where every other gate is registered: the registry
module, and the placement manifest that says where it runs. The shell registry
and the registry document are regenerated from them — never edited by hand.

The positive control follows the same pattern as every other control: it mutates
the *registry file* (one entry removed) and the gate must fail. Mutating the
data rather than the script is deliberate — a control that mutates the code under
test proves less than a control that mutates the thing the gate reads.

## The summary lives in the runner

`sgate run-all --summary` prints one line per failed gate and one line for the
result. It is a flag on the existing runner rather than a wrapper script: a
wrapper would re-derive "which gate failed and why" from the runner's output,
which is the second implementation of a thing the runner already knows.

## The rules live in a file

`CODING_STANDARDS.md` holds the judgement rules a reviewing agent applies. Each
rule names the gate that enforces it, so a rule that matters is checkable rather
than believed. The file is a document and can go stale the way documents do —
which is why the rules are paired with the checks rather than left alone.

## Rollback risk

Low, and bounded in three places.

- The origin checks change *which* tooling is used. If a checkout relied on
  another checkout's installed package, the preflight now fails it. That is the
  intended behaviour, and the fix is a local install.
- The catalogue verification compares an export with a committed file. A
  difference in the export format between the producing task and the verifying
  task would fail a clean tree; both call the same export, so there is one
  format.
- The write-path gate fails a tree that adds a direct writer without an entry.
  The escape is the entry, and the entry carries the reason — which is the
  artefact the change wanted.
