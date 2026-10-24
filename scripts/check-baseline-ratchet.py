#!/usr/bin/env python3
"""check-baseline-ratchet.py — a detekt baseline may shrink, never grow.

## Migration note

Migrated from argparse to Click (2026-10-17). CLI surface is preserved;
the `--baseline`, `--config`, and `--max-growth` flags are all functional
equivalents of the argparse argument definitions.

Why this exists (2026-10-04): `config/detekt/baseline-shared.xml` held 413
suppressed findings and nothing anywhere compared it to its previous size. A
baseline is a ratchet by nature — each entry is a debt someone accepted — but
without this check it is a dumping ground: enabling a new rule, or writing code
that violates an existing one, silently appends an entry and stays green. That
is how "detekt reports 0 findings" came to mean "detekt reports 0 findings
outside the baseline", which is not the same claim.

The check is deliberately one-directional. Removing entries is always allowed
(that's the debt being paid down); adding them is a failure that must be
justified in review. This is the industry-standard replacement for a mass
baseline purge: ratchet, don't purge.

Contract:
  * Baseline file is compared against its state at the **merge-base** with the
    base branch — the commit where this branch forked from `main`. A PR that
    grows the baseline fails, naming the delta.
  * The base branch is `origin/main`, then `main`; `--base` and
    `SGATE_BASE_REF` override that. When neither exists (a shallow checkout,
    a single-branch repository) the comparison falls back to `HEAD` and the
    gate says so — the fallback is the file against itself, and that has to be
    visible rather than silent.
  * `git` is required. Without it the check fails loudly rather than skipping —
    a check that silently passes when it cannot run is the defect class this
    whole change is about.
  * A brand-new baseline file (first commit) is allowed once, then ratchets.

## What this does NOT check — read before trusting it

**This proves "this change did not grow the baseline". It does not prove
"detekt is green".** Those are different claims, and for a long time the two
were treated as one.

It never runs detekt. It compares a file against git. So a change that adds
violating code and does not touch the baseline is invisible to it — correctly,
by its own contract, and silently.

That gap was load-bearing once. On 2026-10-16, 60 findings existed in
`:shared` and `:androidApp` that the baseline did not cover, and all four links
of the chain that let them sit there were open at once:

  1. violating code was written, and the baseline was not regenerated;
  2. this ratchet reported green, because the baseline file did not change;
  3. `:shared:detekt` FAILED;
  4. CI did not notice, because the Gradle step ran as
     `./gradlew … 2>&1 | tee build.log` and, without `pipefail`, a pipeline
     returns the exit code of `tee` — always 0.

So "the ratchet is green" and "detekt is green" diverged with nothing between
them. See docs/decisions/2026-10-16-ci-gradle-step-cannot-fail.md.

**Where "detekt is green" is actually enforced:**

  * locally — `check.sh:189` runs the module detekt tasks and fails on error,
    so `./check.sh` is a real signal;
  * in CI — only once the Gradle step's exit code survives. Until then, a green
    CI run says nothing about lint, and the only honest reading of it is "the
    Python gates passed".

The cheapest way to use this file honestly: treat it as a *budget* check — it
tells you the debt floor is not being lowered. To know whether the code is
actually clean, run `./check.sh`, not this script.

Usage:
  python3 scripts/check-baseline-ratchet.py [--baseline <path>] [--max-growth N]

Exit codes:
  0 — baseline did not grow (or shrank)
  1 — baseline grew, or the check could not run
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

import click

from sgate.commands.base import CLEAN, VALIDATION_FAILURE, register, run

# Detekt baseline entries look like:
#   <ID>RuleName:File.kt:Fqcn$signature</ID>
# One <ID> element per suppressed finding. Counting them is the ratchet unit —
# counting lines would break when detekt rewraps a long signature.
_ID_RE = re.compile(r"<ID>.*?</ID>", re.DOTALL)

# Module-level so the paths do not depend on the caller's working directory.
ROOT = Path(__file__).resolve().parent.parent

# detekt.yml shape:
#   style:                       <- ruleset header, indent 0
#     BackingPropertyNaming:     <- rule header, indent 2
#       active: false            <- the rule's own flag, indent 4
# The indent is load-bearing and the distinction is easy to get wrong: the same
# file carries ruleset-level `active:` at indent 2, and a parser that keys on
# the nearest preceding header regardless of indent reports every ruleset's
# flag as a rule's. That mistake was made while writing this check and it
# reported 11 "rules" for the file's 5.
_RULE_HEADER_RE = re.compile(r"^  ([A-Za-z][\w-]*):\s*$")
_ACTIVE_FALSE_RE = re.compile(r"^\s+active:\s*false\s*$")


class GitUnavailable(RuntimeError):
    """Raised when git is not available or the git command fails."""


def disabled_rules(config_text: str) -> dict[str, int]:
    """Map rule name -> 1 for every rule declared `active: false`.

    A rule's flag is only read while the rule header is the innermost open
    block: a line at indent <= 2 closes it. Comments between the header and the
    flag are ignored, which is why every rule in this repository that needs a
    reason for its decision carries a comment there.
    """
    out: dict[str, int] = {}
    current: str | None = None
    for line in config_text.splitlines():
        if line.strip().startswith("#"):
            continue
        if current is not None and line.strip() and not line.startswith("    "):
            # Dedented back to (or past) the rule header: this rule is closed.
            if _RULE_HEADER_RE.match(line) or not line.startswith("  "):
                current = None
        header = _RULE_HEADER_RE.match(line)
        if header:
            current = header.group(1)
            continue
        if current and _ACTIVE_FALSE_RE.match(line):
            out[current] = 1
    return out


def count_entries(text: str) -> int:
    return len(_ID_RE.findall(text))


def entries(text: str) -> set[str]:
    return {m.strip() for m in _ID_RE.findall(text)}


def git_show(path: Path, rev: str = "HEAD") -> str | None:
    """Return the committed content of `path`, or None if it did not exist."""
    try:
        rel = path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        rel = path.as_posix()
    try:
        out = subprocess.run(
            ["git", "show", f"{rev}:{rel}"],
            capture_output=True,
            text=True,
            check=False,
            cwd=ROOT,
        )
    except FileNotFoundError:
        raise GitUnavailable("git not found — cannot verify the baseline ratchet") from None
    if out.returncode != 0:
        return None
    return out.stdout


#: The refs the base branch is looked for under, in the order they are tried.
#:
#: `origin/main` first: a working copy that has a remote is the normal case, and
#: the remote's main is the branch the pull request targets. `main` after it, for
#: the checkout that has no remote — a fixture, or a clone whose remote was
#: removed.
_BASE_CANDIDATES = (
    "origin/main",
    "main",
    "refs/remotes/origin/main",
    "refs/heads/main",
)


def _git(*args: str) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            ["git", *args],
            capture_output=True,
            text=True,
            check=False,
            cwd=ROOT,
        )
    except FileNotFoundError:
        raise GitUnavailable("git not found — cannot verify the baseline ratchet") from None


def ref_exists(ref: str) -> bool:
    """Whether `ref` resolves to a commit in this repository."""
    out = _git("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}")
    return out.returncode == 0


def merge_base(base: str) -> str | None:
    """The commit where `base` and HEAD share their history, or None.

    This is the common ancestor, and it is what a pull request's diff is
    measured against: everything from here to HEAD is the branch's own work.
    """
    out = _git("merge-base", base, "HEAD")
    if out.returncode != 0:
        return None
    found = out.stdout.strip()
    return found or None


def resolve_comparison_ref(explicit: str | None) -> tuple[str, str]:
    """The revision the baseline is compared against, and a label for it.

    ## Why not HEAD

    The baseline used to be compared with `HEAD`, and that made the gate
    vacuous in the place it matters. On a pull request the working tree *is*
    `HEAD` — CI checks out a commit and the file on disk is that commit's file
    — so the comparison was "the file against itself", and a branch could grow
    the baseline by a hundred entries without the gate noticing.

    The revision that answers the question is the **merge-base**: the commit
    where the branch forked from the base branch. The baseline at that commit
    is the debt the branch inherited; the baseline in the working tree is the
    debt the branch leaves behind. The difference is the branch's own growth,
    which is exactly what a ratchet is supposed to measure.

    ## When there is no base branch

    A shallow checkout has no `origin/main`, and a repository with one branch
    has no merge-base to find. In that case the gate falls back to `HEAD` and
    **says so**: a check that silently degrades to "compare the file with
    itself" is the defect this change removes, so the fallback is announced
    rather than taken quietly.
    """
    if explicit:
        if not ref_exists(explicit):
            return "HEAD", f"HEAD (the requested base '{explicit}' does not exist)"
        found = merge_base(explicit)
        if not found:
            return "HEAD", f"HEAD (no merge-base with '{explicit}')"
        return found, f"merge-base({explicit}, HEAD) = {found[:12]}"

    from_env = os.environ.get("SGATE_BASE_REF", "").strip()
    if from_env:
        if not ref_exists(from_env):
            return "HEAD", f"HEAD (SGATE_BASE_REF='{from_env}' does not exist)"
        found = merge_base(from_env)
        if not found:
            return "HEAD", f"HEAD (no merge-base with SGATE_BASE_REF='{from_env}')"
        return found, f"merge-base({from_env}, HEAD) = {found[:12]}"

    for candidate in _BASE_CANDIDATES:
        if not ref_exists(candidate):
            continue
        found = merge_base(candidate)
        if not found:
            continue
        return found, f"merge-base({candidate}, HEAD) = {found[:12]}"

    return "HEAD", "HEAD (no base branch found — the branch's own growth is invisible)"


def rule_of_entry(entry: str) -> str:
    """The rule name an `<ID>` belongs to.

    Entries look like `RuleName:File.kt:Fqcn$signature`. A rule name cannot
    contain a colon, so the first colon delimits it. The signature half is
    free-form and is not parsed.

    The `<ID>` wrapper is stripped first. `entries()` deliberately keeps the
    tags so that the growth report prints the element verbatim, which means
    anything that wants the *rule* has to remove them itself — slicing the
    tagged string at its first colon silently yields `<ID>RuleName`, which
    matches nothing. That is not hypothetical: it is what this function did the
    first time, and the invariant it fed reported zero phantoms on a baseline
    that had one planted in it.
    """
    body = entry.strip()
    if body.startswith("<ID>"):
        body = body[len("<ID>") :]
    if body.endswith("</ID>"):
        body = body[: -len("</ID>")]
    return body.split(":", 1)[0]


def suppressed_for_disabled(text: str, disabled: dict[str, int]) -> list[str]:
    """Entries in `text` whose rule is declared `active: false`."""
    return sorted(
        {e for e in entries(text) if disabled.get(rule_of_entry(e))}
    )


@click.command()
@click.option(
    "--baseline",
    "baseline",
    multiple=True,
    default=None,
    help="baseline file to ratchet; repeatable. Default: both module baselines.",
)
@click.option(
    "--config",
    "config",
    default="config/detekt/detekt.yml",
    help=(
        "detekt config to read `active: false` from. Default: detekt.yml. "
        "A module on a different config must be named explicitly or the "
        "disabled set will be wrong for it."
    ),
)
@click.option(
    "--max-growth",
    "max_growth",
    type=int,
    default=0,
    help="entries allowed to be added per file per run (default: 0)",
)
@click.option(
    "--base",
    "base",
    default=None,
    help=(
        "the branch the baseline is compared against. The comparison is made "
        "against the merge-base of this branch and that one, so the delta the "
        "gate measures is the branch's own work. Default: origin/main, then "
        "main; SGATE_BASE_REF overrides it."
    ),
)
@click.pass_context
def cli(
    ctx: click.Context,
    baseline: tuple[str, ...],
    config: str,
    max_growth: int,
    base: str | None,
) -> None:
    """Fail when a detekt baseline grows."""
    # Both module baselines are ratcheted. This originally watched only
    # `baseline-shared.xml`, which left `baseline-desktopApp.xml` free to grow
    # unchecked — and it had already drifted from 3 entries to 32 before anyone
    # noticed. A ratchet that watches one of the two files is a half-ratchet.
    baselines = (
        [Path(p) for p in baseline]
        if baseline
        else [
            ROOT / "config" / "detekt" / "baseline-shared.xml",
            ROOT / "config" / "detekt" / "baseline-desktopApp.xml",
            ROOT / "mcp-server" / "detekt-baseline.xml",
        ]
    )
    config_path = ROOT / config
    if not config_path.is_file():
        click.echo(f"ERROR: detekt config not found: {config_path}", err=True)
        raise ctx.exit(VALIDATION_FAILURE)
    disabled = disabled_rules(config_path.read_text(encoding="utf-8"))

    try:
        comparison_ref, comparison_label = resolve_comparison_ref(base)
    except GitUnavailable as exc:
        click.echo(f"ERROR: {exc}", err=True)
        raise ctx.exit(VALIDATION_FAILURE) from None

    click.echo(f"check-baseline-ratchet: comparing against {comparison_label}")
    if comparison_ref == "HEAD":
        click.echo(
            "check-baseline-ratchet: WARNING — no base branch to measure the "
            "branch's own growth against. The comparison below is the working "
            "tree against HEAD, which on a pull request is the file against "
            "itself."
        )

    failed = False
    try:
        for bp in baselines:
            if not _check_one(bp, max_growth, disabled, comparison_ref, comparison_label):
                failed = True
    except GitUnavailable as exc:
        click.echo(f"ERROR: {exc}", err=True)
        raise ctx.exit(VALIDATION_FAILURE) from None
    if failed:
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


def _check_one(
    baseline: Path,
    max_growth: int,
    disabled: dict[str, int],
    comparison_ref: str = "HEAD",
    comparison_label: str = "HEAD",
) -> bool:
    if not baseline.is_file():
        click.echo(f"ERROR: baseline not found: {baseline}", err=True)
        return False

    current_text = baseline.read_text(encoding="utf-8")
    current = count_entries(current_text)
    name = baseline.name

    # A phantom invariant, checked before the ratchet. An entry for a rule that
    # is switched off is not debt, it is a claim the build can no longer make:
    # detekt will never report it, so it neither fails nor protects anything,
    # and it inflates the count the ratchet compares. 90 of 503 entries were
    # exactly this when the invariant was added — 53 for BackingPropertyNaming
    # and 26 for Filename, both disabled in detekt.yml with a written reason.
    # Because the ratchet only ever fails on *growth*, those 90 free entries
    # were a hole: 90 units of real growth would have passed it.
    phantoms = suppressed_for_disabled(current_text, disabled)
    if phantoms:
        by_rule: dict[str, int] = {}
        for item in phantoms:
            rule = rule_of_entry(item)
            by_rule[rule] = by_rule.get(rule, 0) + 1
        click.echo("")
        click.echo(
            f"check-baseline-ratchet: FAIL — {name} suppresses "
            f"{len(phantoms)} finding(s) for rules declared `active: false`"
        )
        for rule, n in sorted(by_rule.items(), key=lambda kv: -kv[1]):
            click.echo(f"  {n:4}  {rule}")
        click.echo("")
        click.echo("These entries suppress nothing: the rule is switched off, so it")
        click.echo("cannot report. They inflate the count the ratchet compares, which")
        click.echo("means the suppressed count also under-reports real growth by this")
        click.echo("much. Fix by regenerating the baseline — a disabled rule")
        click.echo("contributes no current findings, so the entries disappear on their")
        click.echo("own:")
        click.echo(f"  ./gradlew :{name.replace('baseline-', '').replace('.xml', '')}:detektBaseline")
        click.echo("")
        click.echo("If instead a rule was disabled by mistake and the findings are")
        click.echo("real, set it back to `active: true` in config/detekt/detekt.yml and")
        click.echo("say why in a comment. Do not silence this check.")
        return False

    try:
        committed_text = git_show(baseline, comparison_ref)
    except GitUnavailable:
        raise

    if committed_text is None:
        click.echo(
            f"check-baseline-ratchet: {name} is new in this branch "
            f"({current} entries) — ratcheting from this commit"
        )
        return True

    committed = count_entries(committed_text)
    growth = current - committed

    if growth > max_growth:
        added = sorted(entries(current_text) - entries(committed_text))
        click.echo("")
        click.echo(
            f"check-baseline-ratchet: FAIL — {name} grew by {growth} "
            f"entry(ies) ({committed} -> {current})"
        )
        click.echo(f"  measured against {comparison_label}")
        click.echo("")
        click.echo("Newly suppressed findings:")
        for item in added[:20]:
            click.echo(f"  + {item}")
        if len(added) > 20:
            click.echo(f"  ... and {len(added) - 20} more")
        click.echo("")
        click.echo("A baseline is a ratchet, not a place to park new debt. Either")
        click.echo("fix the finding, or -- if a rule was just enabled and the")
        click.echo("pre-existing volume is genuinely being baselined on purpose --")
        click.echo("pass --max-growth N in a commit whose message says why.")
        return False

    if growth < 0:
        click.echo(
            f"check-baseline-ratchet: OK -- {name} shrank by {-growth} "
            f"entry(ies) ({committed} -> {current})"
        )
    else:
        click.echo(f"check-baseline-ratchet: OK -- {name} unchanged ({current} entries)")
    return True




if __name__ == "__main__":
    register(cli)
    # Use standalone_mode=False so ctx.exit() raises SystemExit with the real code,
    # not Click's internal sys.exit(0) that standalone_mode=True always calls.
    raise SystemExit(run(cli))
