#!/usr/bin/env python3
"""check-maestro-flow-qualification.py — a flow in a blocking scope is qualified.

The problem
-----------
A new Maestro flow, or one changed in the ways that make it fail (selectors,
timing, navigation, setup, assertions), can be added to an automated suite and
run in CI for the first time as part of a blocking scope. A flow that is flaky
or that was never shown to pass reliably then fails the suite for a reason the
suite did not have before — and a failure nobody has seen before is a failure
nobody can tell apart from a regression.

So a flow does not enter a blocking automated scope on the strength of its
first green run. It enters on the strength of a small, bounded run that showed
it passes repeatedly, in the same documented environment: the qualification.

What this gate holds
--------------------
Every flow in an automated blocking scope — every flow in the catalogue except
flows tagged ``manual`` or ``auth-live`` (the automated ``full`` suite and the
``smoke`` subset of it) — is admitted in one of three ways:

1. **Baselined.** The flow was in a blocking scope when this rule was
   introduced and its content (the flow and the helpers it runs, transitively)
   is unchanged since then. The baseline is
   ``config/docs/maestro-flow-qualification-baseline.yaml``; a fingerprint that
   no longer matches is not an admission, it is a change that needs
   qualification.
2. **Qualified.** ``config/docs/maestro-flow-qualification.yaml`` records a
   qualification run for the flow's current fingerprint: at least
   ``required_attempts`` consecutive attempts, every attempt represented,
   every attempt a passing test run (no ``test_failure``, no
   ``infra_failure``), in one documented environment and build. A test failure
   or an infrastructure failure in the run means the flow is not qualified —
   an infrastructure failure is never counted as a successful test attempt,
   and an earlier failure followed by a pass is a flaky flow, not a qualified
   one.
3. **Excepted.** The same file records an explicit, evidence-backed exception
   for the flow's current fingerprint: a reason and a reference to the
   evidence (an issue or a note). An exception is a decision, not a silence.

The gate reads the flow and its helpers to compute the fingerprint, so a
change to a helper requalifies every flow that runs it. It never rewrites the
baseline or the qualification file.

Usage:
    python3 scripts/check-maestro-flow-qualification.py [--json] [--check]
        [--root DIR] [--catalogue FILE] [--baseline FILE] [--evidence FILE]
        [--init-baseline] [--init-evidence]

Exit codes:
    0 — every flow in a blocking automated scope is admitted
    1 — an unqualified, flaky, stale or unexcepted flow is in a blocking scope
    2 — the catalogue, baseline or evidence file could not be read
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import re
import sys
from dataclasses import dataclass

import click

# This checkout's packages first: an editable install of a sibling worktree is a
# different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

import yaml  # noqa: E402

from emulator.maestro._flow import (  # noqa: E402
    AUTOMATED_FULL_EXCLUDE_TAGS,
    flow_tags,
)
from sgate.commands.base import run  # noqa: E402

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CATALOGUE = ROOT / "config" / "docs" / "maestro-flows.txt"
DEFAULT_BASELINE = ROOT / "config" / "docs" / "maestro-flow-qualification-baseline.yaml"
DEFAULT_EVIDENCE = ROOT / "config" / "docs" / "maestro-flow-qualification.yaml"

#: A reference inside a flow/helper: `file: path.yaml` or `- runFlow: path.yaml`.
_FILE_REF = re.compile(r"(?m)^\s*(?:-\s*)?file:\s*['\"]?([^\s#'\"]+\.ya?ml)['\"]?\s*$")
_RUNFLOW_REF = re.compile(r"(?m)^\s*-\s*runFlow:\s*['\"]?([^\s#'\"]+\.ya?ml)['\"]?\s*$")


@dataclass(frozen=True)
class Finding:
    flow: str
    kind: str
    detail: str


def read_catalogue(path: pathlib.Path) -> list[str]:
    entries: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        entries.append(stripped)
    return entries


def is_automated_blocking(flow_path: pathlib.Path) -> bool:
    """A flow in any automated blocking scope: not manual, not auth-live."""
    tags = set(flow_tags(flow_path))
    return tags.isdisjoint(AUTOMATED_FULL_EXCLUDE_TAGS)


def blocking_scope_flows(catalogue: list[str], root: pathlib.Path) -> tuple[list[str], list[Finding]]:
    findings: list[Finding] = []
    flows: list[str] = []
    for flow in catalogue:
        path = root / flow
        if not path.is_file():
            findings.append(Finding(flow, "flow file missing", f"{flow} — the catalogue names a flow that is not in the tree"))
            continue
        if is_automated_blocking(path):
            flows.append(flow)
    return flows, findings


def resolve_ref(ref: str, source: pathlib.Path, root: pathlib.Path) -> pathlib.Path | None:
    """Resolve a YAML file reference relative to the file that contains it."""
    if not ref.endswith((".yaml", ".yml")):
        return None
    candidate = (source.parent / ref).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        return None
    return candidate if candidate.is_file() else None


def flow_dependencies(flow_rel: str, root: pathlib.Path) -> list[str]:
    """The flow and every helper it runs, transitively, as repo-relative paths."""
    seen: set[str] = set()
    stack = [flow_rel]
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        path = root / current
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        refs: list[str] = []
        for match in _FILE_REF.finditer(text):
            refs.append(match.group(1))
        for match in _RUNFLOW_REF.finditer(text):
            refs.append(match.group(1))
        for ref in refs:
            resolved = resolve_ref(ref, path, root)
            if resolved is None:
                continue
            rel = resolved.relative_to(root.resolve()).as_posix()
            # Only Maestro YAMLs are part of the flow's contract.
            if rel.startswith("Maestro/") and rel not in seen:
                stack.append(rel)
    return sorted(seen)


def fingerprint(flow_rel: str, root: pathlib.Path) -> str:
    """A stable digest of the flow and its transitive helper dependencies."""
    digest = hashlib.sha256()
    for rel in flow_dependencies(flow_rel, root):
        digest.update(rel.encode("utf-8"))
        digest.update(b"\0")
        try:
            digest.update((root / rel).read_bytes())
        except OSError:
            digest.update(b"<unreadable>")
        digest.update(b"\0")
    return f"sha256:{digest.hexdigest()}"


def read_yaml(path: pathlib.Path) -> dict:
    if not path.is_file():
        return {}
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"{path}: not a mapping")
    return data


def read_baseline(path: pathlib.Path) -> tuple[dict[str, str], set[str]]:
    data = read_yaml(path)
    flows = data.get("flows") or {}
    if not isinstance(flows, dict):
        raise ValueError(f"{path}: flows is not a mapping")
    result: dict[str, str] = {}
    for flow, fp in flows.items():
        if isinstance(flow, str) and isinstance(fp, str):
            result[flow] = fp
    initial = data.get("initial_flows")
    if isinstance(initial, list):
        initial_set = {x for x in initial if isinstance(x, str)}
    else:
        # Legacy/missing field: treat current baseline snapshot as the frozen initial set.
        initial_set = set(result.keys())
    return result, initial_set


def read_evidence(path: pathlib.Path) -> tuple[int, dict[str, dict], dict[str, dict]]:
    data = read_yaml(path)
    required = data.get("required_attempts", 3)
    if type(required) is not int or required < 1:
        required = 3
    qualifications = data.get("qualifications") or {}
    exceptions = data.get("exceptions") or {}
    if not isinstance(qualifications, dict):
        qualifications = {}
    if not isinstance(exceptions, dict):
        exceptions = {}
    return required, qualifications, exceptions


def _env_key(env: dict) -> tuple:
    if not isinstance(env, dict):
        return ()
    # Same documented environment: kind, api, device model.
    return (env.get("kind"), env.get("api"), env.get("device"))


def is_qualified(record: dict, current_fp: str, required: int) -> tuple[bool, str]:
    if not isinstance(record, dict):
        return False, "qualification record is not a mapping"
    if record.get("fingerprint") != current_fp:
        got = record.get("fingerprint", "<none>")
        return False, f"qualification is for fingerprint {got}, current is {current_fp} (requalification required)"
    attempts = record.get("attempts")
    if not isinstance(attempts, list):
        return False, "qualification records no attempts"
    if len(attempts) < required:
        return False, f"qualification has {len(attempts)} attempt(s), {required} required"
    # All attempts in the qualification run must be represented and must be
    # clean passes. Any test_failure = flaky/not qualified. Any infra_failure =
    # not a successful test attempt (run not conclusive). Take the last
    # `required` attempts as the qualifying sequence? "3 consecutive runs" -
    # require at least `required` attempts total and the last `required` to be
    # consecutive passes in the same environment/build; all attempts recorded.
    # Simpler and stricter: every recorded attempt must be a pass and the
    # sequence must be >= required, all in the same environment and build.
    envs = set()
    builds = set()
    mversions = set()
    for i, attempt in enumerate(attempts, start=1):
        if not isinstance(attempt, dict):
            return False, f"attempt {i} is not a mapping"
        num = attempt.get("attempt")
        if type(num) is not int or num != i:
            return False, f"attempt {i} does not have a valid sequential attempt number"
        outcome = attempt.get("outcome")
        if outcome == "test_failure":
            return False, f"attempt {i} was a test failure (a flaky or failing flow is not qualified)"
        if outcome == "infra_failure":
            return False, f"attempt {i} was an infrastructure failure (not a successful test attempt)"
        if outcome != "pass":
            return False, f"attempt {i} has outcome {outcome!r}, not a passing test attempt"
        env = attempt.get("environment")
        if not isinstance(env, dict) or not env.get("kind") or env.get("api") is None:
            return False, f"attempt {i} does not record a documented environment (kind/api)"
        envs.add(_env_key(env))
        build = attempt.get("apk_commit_sha") or attempt.get("commit")
        if not build:
            return False, f"attempt {i} does not record build identity (commit/apk_commit_sha)"
        builds.add(build)
        mv = attempt.get("maestro_version")
        if not mv:
            return False, f"attempt {i} does not record the Maestro version"
        mversions.add(mv)
    if len(envs) > 1:
        return False, "qualification attempts do not share one documented environment"
    if len(builds) > 1:
        return False, "qualification attempts do not share one build identity"
    if len(mversions) > 1:
        return False, "qualification attempts do not share one Maestro version"
    return True, ""


def is_excepted(record: dict, current_fp: str) -> tuple[bool, str]:
    if not isinstance(record, dict):
        return False, "exception record is not a mapping"
    fp = record.get("fingerprint")
    if not fp:
        return False, "exception has no fingerprint (must be tied to the current flow fingerprint)"
    if fp != current_fp:
        return False, f"exception is for fingerprint {fp}, current is {current_fp} (reissue for the changed flow)"
    reason = record.get("reason")
    evidence = record.get("evidence") or record.get("issue")
    if not reason or not str(reason).strip():
        return False, "exception has no reason"
    if not evidence:
        return False, "exception has no evidence reference (issue/evidence)"
    return True, ""


def scan(root: pathlib.Path, catalogue_path: pathlib.Path, baseline_path: pathlib.Path, evidence_path: pathlib.Path) -> tuple[list[Finding], dict[str, int]]:
    try:
        catalogue = read_catalogue(catalogue_path)
    except OSError as exc:
        raise click.ClickException(f"cannot read catalogue {catalogue_path}: {exc}") from exc
    try:
        baseline, initial_baseline = read_baseline(baseline_path) if baseline_path.is_file() else ({}, set())
    except (OSError, ValueError, yaml.YAMLError) as exc:
        raise click.ClickException(f"cannot read baseline {baseline_path}: {exc}") from exc
    try:
        required, qualifications, exceptions = read_evidence(evidence_path)
    except (OSError, ValueError, yaml.YAMLError) as exc:
        raise click.ClickException(f"cannot read evidence {evidence_path}: {exc}") from exc

    flows, findings = blocking_scope_flows(catalogue, root)
    counts = {"flows": len(flows), "baselined": 0, "qualified": 0, "excepted": 0}

    for flow in flows:
        fp = fingerprint(flow, root)
        if flow in baseline and flow in initial_baseline and baseline[flow] == fp:
            counts["baselined"] += 1
            continue

        # Recovery for a changed/non-matching flow happens via qualification or
        # exception for the current fingerprint (including stale baseline flows).
        if flow in qualifications:
            ok, reason = is_qualified(qualifications[flow], fp, required)
            if ok:
                counts["qualified"] += 1
                continue
            findings.append(Finding(flow, "not qualified", reason))
            continue
        if flow in exceptions:
            ok, reason = is_excepted(exceptions[flow], fp)
            if ok:
                counts["excepted"] += 1
                continue
            findings.append(Finding(flow, "invalid exception", reason))
            continue

        if flow in baseline and flow not in initial_baseline:
            findings.append(
                Finding(
                    flow,
                    "baseline expansion",
                    "new flow cannot be admitted by adding it to the qualification baseline; qualify it or add an evidence-backed exception",
                )
            )
        elif flow in baseline:
            findings.append(
                Finding(
                    flow,
                    "stale baseline",
                    f"flow or its helpers changed since the qualification baseline (current {fp}); requalify or add an evidence-backed exception",
                )
            )
        else:
            findings.append(
                Finding(
                    flow,
                    "unqualified",
                    "new or unadmitted flow in a blocking automated scope; qualify it (bounded repeated run) or add an evidence-backed exception",
                )
            )
    return findings, counts


def build_baseline(root: pathlib.Path, catalogue_path: pathlib.Path) -> dict:
    catalogue = read_catalogue(catalogue_path)
    flows, findings = blocking_scope_flows(catalogue, root)
    missing = [f for f in findings if f.kind == "flow file missing"]
    if missing:
        raise click.ClickException("cannot build baseline: " + "; ".join(f.detail for f in missing))
    mapping = {flow: fingerprint(flow, root) for flow in flows}
    flow_names = sorted(mapping.keys())
    return {
        "schema": 1,
        "policy": "flows in automated blocking scopes at rule introduction; a matching fingerprint admits the flow, a change requires qualification",
        "initial_flows": flow_names,
        "flows": dict(sorted(mapping.items())),
    }


@click.command(context_settings={"help_option_names": ["-h", "--help"]})
@click.option("--json", "as_json", is_flag=True, help="Emit JSON output")
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit for findings")
@click.option("--root", type=click.Path(path_type=pathlib.Path), default=ROOT, show_default=False)
@click.option("--catalogue", type=click.Path(path_type=pathlib.Path), default=DEFAULT_CATALOGUE)
@click.option("--baseline", type=click.Path(path_type=pathlib.Path), default=DEFAULT_BASELINE)
@click.option("--evidence", type=click.Path(path_type=pathlib.Path), default=DEFAULT_EVIDENCE)
@click.option("--init-baseline", is_flag=True, help="Write the baseline for current in-scope flows and exit")
@click.option("--init-evidence", is_flag=True, help="Write an empty evidence file if missing and exit")
@click.pass_context
def cli(ctx: click.Context, as_json: bool, check: bool, root: pathlib.Path, catalogue: pathlib.Path, baseline: pathlib.Path, evidence: pathlib.Path, init_baseline: bool, init_evidence: bool) -> None:
    root = root.resolve()
    try:
        if init_baseline:
            data = build_baseline(root, catalogue)
            baseline.parent.mkdir(parents=True, exist_ok=True)
            baseline.write_text("# Maestro flow qualification baseline: pre-rule flows in blocking scopes.\n# This map only shrinks; a changed flow must be qualified or excepted instead.\n" + yaml.safe_dump(data, sort_keys=False, allow_unicode=True), encoding="utf-8")
            click.echo(f"check-maestro-flow-qualification: wrote baseline for {len(data['flows'])} flow(s) to {baseline}")
            raise ctx.exit(CLEAN)
        if init_evidence:
            if not evidence.exists():
                evidence.parent.mkdir(parents=True, exist_ok=True)
                evidence.write_text("# Maestro flow qualification evidence and exceptions.\n# A qualification requires required_attempts consecutive passing test attempts\n# in the same documented environment/build, with every attempt recorded.\n" + yaml.safe_dump({"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}}, sort_keys=False), encoding="utf-8")
                click.echo(f"check-maestro-flow-qualification: wrote empty evidence file to {evidence}")
            else:
                click.echo(f"check-maestro-flow-qualification: evidence file exists: {evidence}")
            raise ctx.exit(CLEAN)

        findings, counts = scan(root, catalogue, baseline, evidence)
    except click.ClickException as exc:
        click.echo(f"check-maestro-flow-qualification: {exc.format_message()}", err=True)
        raise ctx.exit(IO_ERROR)

    if as_json:
        click.echo(json.dumps({"counts": counts, "findings": [f.__dict__ for f in findings]}, indent=2, ensure_ascii=False))
    elif not findings:
        click.echo(
            f"check-maestro-flow-qualification: OK — {counts['flows']} flow(s) in blocking scopes: "
            f"{counts['baselined']} baselined, {counts['qualified']} qualified, {counts['excepted']} excepted"
        )
    else:
        click.echo(f"check-maestro-flow-qualification: {len(findings)} finding(s)")
        for f in findings:
            click.echo(f"  {f.kind}: {f.flow} — {f.detail}")

    if check:
        raise ctx.exit(CLEAN)
    raise ctx.exit(VALIDATION_FAILURE if findings else CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
