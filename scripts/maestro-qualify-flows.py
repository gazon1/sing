#!/usr/bin/env python3
"""maestro-qualify-flows.py — qualify new or changed Maestro flows by repeat runs.

A flow enters a blocking automated scope only after a small, bounded number of
consecutive passing runs in the same documented environment (see
docs/testing/maestro-flow-qualification.md). This tool runs the affected
flow(s) repeatedly, preserves every attempt's existing JUnit/environment
outputs, records identity and outcomes, and can write a qualification record
when the run qualifies the flow.

Every attempt is kept: an initial failure followed by a pass remains visible as
a flaky run and does not qualify the flow. Infrastructure failures (device
unavailable, app install/launch failure, no report produced) are classified
separately and are never counted as successful test attempts.

Usage:
    # list flows that need qualification/admission in blocking scopes
    python3 scripts/maestro-qualify-flows.py --admission-needed --list

    # qualify specific flows (3 consecutive attempts each by default)
    python3 scripts/maestro-qualify-flows.py --flow Maestro/flows/tasks/05-archive-via-menu.yaml

    # qualify all unadmitted blocking-scope flows and record when qualified
    python3 scripts/maestro-qualify-flows.py --admission-needed --record

    # run without updating the evidence file (dry record)
    python3 scripts/maestro-qualify-flows.py --flow path.yaml --record --no-write

Exit codes:
    0 — all selected flows qualified (or --list/--dry-run completed)
    1 — at least one selected flow did not qualify, or a run could not be measured
    2 — arguments/configuration error
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import shlex
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, asdict
from datetime import datetime, timezone

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "scripts" / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

# Reuse gate logic for scope/admission/fingerprint.
import importlib.util

def _load_gate():
    spec = importlib.util.spec_from_file_location(
        "check_maestro_flow_qualification",
        ROOT / "scripts" / "check-maestro-flow-qualification.py",
    )
    mod = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod

GATE = _load_gate()

DEFAULT_CATALOGUE = ROOT / "config" / "docs" / "maestro-flows.txt"
DEFAULT_BASELINE = ROOT / "config" / "docs" / "maestro-flow-qualification-baseline.yaml"
DEFAULT_INITIAL = ROOT / "config" / "docs" / "maestro-flow-qualification-initial.yaml"
DEFAULT_EVIDENCE = ROOT / "config" / "docs" / "maestro-flow-qualification.yaml"
DEFAULT_OUT = ROOT / "build" / "maestro-qualification"
FLOWS_ROOT = ROOT / "Maestro" / "flows"


@dataclass
class Attempt:
    attempt: int
    outcome: str  # pass | test_failure | infra_failure
    reason: str
    junit: str | None
    results_dir: str
    environment: dict | None
    run_summary: dict | None
    return_code: int
    maestro_version: str | None = None


def read_json(path: pathlib.Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def flow_report_outcome(report_path: pathlib.Path) -> tuple[str, str]:
    if not report_path.is_file():
        return "infra_failure", "no JUnit report produced for the flow"
    try:
        root = ET.parse(report_path).getroot()
    except ET.ParseError as exc:
        return "infra_failure", f"JUnit report unreadable: {exc}"
    cases = list(root.iter("testcase"))
    if not cases:
        return "infra_failure", "JUnit report contains no testcase"
    for case in cases:
        failure = case.find("failure")
        error = case.find("error")
        status = (case.get("status") or "").upper()
        if failure is not None or error is not None or status in {"ERROR", "FAILURE", "FAILED"}:
            msg = ""
            node = failure if failure is not None else error
            if node is not None:
                msg = " ".join(((node.get("message") or "") + " " + (node.text or "")).split())[:240]
            return "test_failure", msg or f"testcase status {status or 'failed'}"
    return "pass", "test passed"


def classify_attempt(flow_rel: str, results_dir: pathlib.Path, return_code: int) -> Attempt:
    stem = pathlib.PurePosixPath(flow_rel).stem
    junit = results_dir / f"{stem}.xml"
    env = read_json(results_dir / "environment.json")
    summary = read_json(results_dir / "run-summary.json")
    outcome, reason = flow_report_outcome(junit)
    required_summary = ("passed", "failed", "not_measured", "retried")
    if not isinstance(summary, dict) or not all(isinstance(summary.get(k), list) for k in required_summary):
        outcome, reason = "infra_failure", "run-summary.json missing or invalid"
    else:
        passed = summary.get("passed") or []
        failed = summary.get("failed") or []
        not_measured = summary.get("not_measured") or []
        retried = summary.get("retried") or []
        if stem in not_measured:
            outcome, reason = "infra_failure", "flow not measured (app/device unavailable)"
        elif stem in retried and outcome == "pass":
            outcome, reason = "test_failure", "flow retried internally during attempt (not a clean pass)"
        elif stem in failed and outcome == "pass":
            outcome, reason = "test_failure", "run summary reports flow as failed despite passing JUnit"
        elif outcome == "pass" and stem not in passed:
            outcome, reason = "infra_failure", "run summary does not confirm flow passed"
    if not junit.is_file():
        outcome, reason = "infra_failure", reason or "no JUnit report produced for the flow"
    if return_code != 0 and outcome == "pass":
        outcome, reason = "infra_failure", f"maestro runner exited with code {return_code} despite a passing report"
    return Attempt(
        attempt=0,
        outcome=outcome,
        reason=reason,
        junit=str(junit.relative_to(ROOT)) if junit.exists() and _inside(junit, ROOT) else (str(junit) if junit.exists() else None),
        results_dir=str(results_dir.relative_to(ROOT)) if _inside(results_dir, ROOT) else str(results_dir),
        environment=env if isinstance(env, dict) else None,
        run_summary=summary if isinstance(summary, dict) else None,
        return_code=return_code,
        maestro_version=None,
    )


def _inside(path: pathlib.Path, root: pathlib.Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def snapshot_flow(flow_rel: str) -> tuple[pathlib.Path, pathlib.Path]:
    """Copy the tree into an immutable snapshot and return the flow inside it.

    The attempts of one qualification have to run the same content. Comparing
    the fingerprint before and after the attempts is not enough: a flow edited
    while it is being qualified and restored before the last attempt looks
    unchanged from the outside, and the record would claim three passing runs
    for a version that only one attempt ever saw.

    The whole `Maestro/` tree is copied, not just the flow's dependency
    closure: a flow can reach a helper script the closure does not track, and
    the snapshot has to be what the run actually reads. The layout is kept, so
    a helper reference resolves inside the snapshot exactly as it resolves in
    the tree.

    Returns the snapshot root and the flow path inside it.
    """
    flow_key = pathlib.PurePosixPath(flow_rel).with_suffix("")
    # The snapshot lives inside the checkout: the runner reports flow paths
    # relative to the repository root and refuses a path that escapes it.
    snapshot_root = ROOT / "build" / "maestro-qualification-snapshot" / flow_key.as_posix().replace("/", "__")
    if snapshot_root.exists():
        shutil.rmtree(snapshot_root)
    snapshot_root.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        ROOT / "Maestro",
        snapshot_root / "Maestro",
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
    )
    return snapshot_root, snapshot_root / flow_rel


def _untracked_issues(untracked: list[str]) -> list[str]:
    """The refusal message for references the qualification tree cannot cover."""
    return [
        f"{issue} — keep every reference inside the qualification tree "
        f"({GATE.QUALIFICATION_TREE}/) and requalify"
        for issue in untracked
    ]


def _refusal_summary(flow: str, issues: list[str]) -> dict:
    """The report entry for a flow the runner refused to qualify."""
    return {
        "flow": flow,
        "qualified": False,
        "attempts_passed": False,
        "attempts_run": 0,
        "outcomes": [],
        "record_issues": issues,
        "record": None,
    }


def run_one_attempt(flow_rel: str, attempt: int, out_base: pathlib.Path, serial: str | None, engine: str | None, skip_install: bool, allow_stale_apk: bool, extra: list[str], flow_path: pathlib.Path | None = None) -> Attempt:
    """Run one attempt of a flow.

    `flow_path` is the flow the attempt runs. The runner passes the immutable
    snapshot of the tree, so every attempt of one qualification runs the same
    content even when the working tree changes underneath the run.
    """
    flow_path = flow_path or (ROOT / flow_rel)
    flow_key = pathlib.PurePosixPath(flow_rel).with_suffix("")
    attempt_dir = out_base / flow_key / f"attempt-{attempt}"
    # Never read stale artifacts from a previous invocation of this attempt slot.
    if attempt_dir.exists():
        shutil.rmtree(attempt_dir)
    attempt_dir.mkdir(parents=True, exist_ok=True)
    cmd = [sys.executable, "-m", "emulator.maestro", "--flow", str(flow_path), "--junit-dir", str(attempt_dir)]
    if serial:
        cmd += ["--serial", serial]
    if engine:
        cmd += ["--engine", engine]
    if skip_install:
        cmd += ["--skip-install"]
    if allow_stale_apk:
        cmd += ["--allow-stale-apk"]
    # Qualification requires each attempt to pass cleanly; disable internal retries
    # so a retry-recovered pass cannot count as a successful attempt.
    cmd += ["--maestro-max-retries", "0"]
    cmd += extra
    env = os.environ.copy()
    # ensure this checkout's scripts/src is importable
    env["PYTHONPATH"] = os.pathsep.join(filter(None, [str(SRC), env.get("PYTHONPATH", "")]))
    engine_used = effective_engine(engine)
    # The version is read before the attempt: the evidence has to name the build
    # that ran it, and a version read afterwards names a build nobody ran. An
    # undetected version is not a smaller piece of evidence -- the admission
    # gate rejects a record without it -- so the attempt is refused instead of
    # spending device time on evidence nothing can accept.
    mv = maestro_version(engine_used)
    if mv is None:
        result = Attempt(
            attempt=attempt,
            outcome="infra_failure",
            reason=f"the {engine_used} version could not be detected before the attempt",
            junit=None,
            results_dir=(
                str(attempt_dir.relative_to(ROOT)) if _inside(attempt_dir, ROOT) else str(attempt_dir)
            ),
            environment=None,
            run_summary=None,
            return_code=0,
            maestro_version=None,
        )
        print(f"    -> {result.outcome}: {result.reason}")
        return result
    print(f"  attempt {attempt}: {' '.join(shlex.quote(c) for c in cmd)}")
    cp = subprocess.run(cmd, cwd=ROOT, env=env)
    result = classify_attempt(flow_rel, attempt_dir, cp.returncode)
    result.attempt = attempt
    result.maestro_version = mv
    print(f"    -> {result.outcome}: {result.reason}")
    return result


#: The engine's own banner: the engine name, and the version right after it.
#:
#: The version has to belong to the engine. A number that follows some other
#: word ("Maestro version unavailable (build 1.2)") is a number somebody
#: else printed, and reading it as the engine's version would put a version
#: in the record that nobody established.
_ENGINE_BANNER = re.compile(
    r"^\s*maestro[-\w.]*\s*(?:version\s+)?v?(\d+\.\d+(?:\.\d+)*)",
    re.IGNORECASE,
)
#: A line that starts with a version number: the bare version some builds print.
_VERSION_LINE = re.compile(r"^\s*v?(\d+\.\d+(?:\.\d+)*)(?![\d.])")


def _version_token(text: str) -> str | None:
    """The version in a `--version` output, or None if it names none.

    The tool's own banner wins over any other line: a JVM warning printed
    above the banner must not become the version of the tool that ran the
    attempt.

    Output that names no version names no version. A line without a version
    number is not a version -- accepting one would turn any output at all
    ("Maestro version unavailable") into a detected version, and the
    qualification would carry a version nobody established. So a version is
    read only from the engine's banner, or from a line that is a version and
    nothing else.
    """
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    for line in lines:
        match = _ENGINE_BANNER.match(line)
        if match:
            return match.group(1)
    for line in lines:
        # A line that is a version number, with nothing before it: the bare
        # version some builds print. A version that follows other words
        # belongs to something else and is not the engine's.
        match = _VERSION_LINE.match(line)
        if match:
            return match.group(1)
    return None


def effective_engine(engine: str | None) -> str:
    """The engine that will run the attempts.

    The same order the runner itself uses (`--engine`, then `MAESTRO_ENGINE`,
    then the Maestro CLI), so the version recorded with an attempt is the
    version of the engine that actually ran it.
    """
    return engine or os.environ.get("MAESTRO_ENGINE") or "maestro"


def maestro_version(engine: str | None = None) -> str | None:
    """Actual version of the selected Maestro engine, or None if undetected.

    Qualification evidence must identify the engine that executed the attempt,
    so only the selected engine is probed and no pinned/other-engine substitute
    is used: a version of a build that did not run is not the version of the
    attempt. An undetected version is not a smaller piece of evidence either —
    the admission gate rejects a record without it, so the runner refuses to
    write one.
    """
    selected = engine or "maestro"
    if selected == "maestro-runner":
        try:
            from emulator.maestro import _engine as eng

            v = eng.runner_version()
            if v:
                return v
        except Exception:
            pass
        cmd = ["maestro-runner", "--version"]
    else:
        cmd = ["maestro", "--version"]

    try:
        cp = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if cp.returncode != 0:
        return None
    return _version_token((cp.stdout or "").strip() or (cp.stderr or "").strip())


def admission_needed(
    root: pathlib.Path,
    catalogue: pathlib.Path,
    baseline: pathlib.Path,
    evidence: pathlib.Path,
    initial: pathlib.Path,
) -> list[str]:
    try:
        findings, _counts = GATE.scan(root, catalogue, baseline, evidence, initial)
    except GATE.click.ClickException as exc:
        # The gate refuses a tampered artifact -- a baseline that does not
        # match its pinned digest, a frozen set that does not match its pin.
        # Nothing can be qualified in that state, and the reason is said
        # instead of a traceback.
        print(
            f"the qualification gate refused to run: {exc.format_message()}",
            file=sys.stderr,
        )
        raise SystemExit(2) from exc
    seen = []
    for f in findings:
        if f.kind in {"flow file missing"}:
            continue
        if f.flow not in seen:
            seen.append(f.flow)
    return seen


def build_qualification_record(flow_rel: str, attempts: list[Attempt], fingerprint: str) -> dict:
    """The record for a flow, carrying the fingerprint the attempts certify.

    The fingerprint is passed in, not computed here: it is the one taken
    before the first attempt, so the record names the flow that was actually
    run and not whatever the tree looks like when the record is built.
    """
    fp = fingerprint
    # identity: prefer consistent values across attempts
    env = None
    commit = None
    apk = None
    device = None
    kind = None
    api = None
    serial = None
    for a in attempts:
        e = a.environment or {}
        if env is None and e:
            env = {
                "kind": e.get("kind"),
                "api": e.get("api"),
                "device": e.get("device"),
                "serial": e.get("serial"),
            }
            kind, api, device = e.get("kind"), e.get("api"), e.get("device")
            serial = e.get("serial")
        commit = commit or e.get("commit")
        apk = apk or e.get("apk_commit_sha")
    attempt_records = []
    for a in attempts:
        e = a.environment or {}
        attempt_records.append({
            "attempt": a.attempt,
            "outcome": a.outcome,
            "reason": a.reason,
            "results_dir": a.results_dir,
            "junit": a.junit,
            "environment": {
                "kind": e.get("kind"),
                "api": e.get("api"),
                "device": e.get("device"),
                "serial": e.get("serial"),
            },
            "commit": e.get("commit"),
            "apk_commit_sha": e.get("apk_commit_sha"),
            "maestro_version": a.maestro_version,
            "return_code": a.return_code,
        })
    versions = [a.maestro_version for a in attempts]
    mv = versions[0] if versions and all(v and v == versions[0] for v in versions) else None
    return {
        "fingerprint": fp,
        "qualified_at": datetime.now(timezone.utc).date().isoformat(),
        "environment": {"kind": kind, "api": api, "device": device, "serial": serial},
        "commit": commit,
        "apk_commit_sha": apk,
        "maestro_version": mv,
        "attempts": attempt_records,
    }


def record_issues(record: dict) -> list[str]:
    issues = []
    attempts = record.get("attempts") or []
    envs = set()
    builds = set()
    mversions = set()
    for i, attempt in enumerate(attempts, start=1):
        if not isinstance(attempt, dict):
            issues.append(f"attempt {i} is not a mapping")
            continue
        env = attempt.get("environment") or {}
        if not isinstance(env, dict) or not env.get("kind") or env.get("api") is None:
            issues.append(f"attempt {i} does not record a documented environment (kind/api)")
        if not isinstance(env, dict) or not env.get("serial"):
            # The device is part of the identity: a run that does not name the
            # device it ran on cannot be told apart from a run on another one.
            issues.append(f"attempt {i} does not record the device it ran on (serial)")
        envs.add(
            (
                env.get("kind") if isinstance(env, dict) else None,
                env.get("api") if isinstance(env, dict) else None,
                env.get("device") if isinstance(env, dict) else None,
                # The device itself, not only its model: two runs on two
                # devices of the same model are two runs on two devices, and
                # the flakiness a qualification hunts is often device-specific.
                env.get("serial") if isinstance(env, dict) else None,
            )
        )
        build = attempt.get("apk_commit_sha") or attempt.get("commit")
        if not build:
            issues.append(f"attempt {i} does not record build identity (commit/apk_commit_sha)")
        builds.add(build)
        mv = attempt.get("maestro_version")
        if not mv:
            issues.append(f"attempt {i} does not record the Maestro version")
        mversions.add(mv)
    if attempts and len(envs) > 1:
        issues.append("qualification attempts do not share one documented environment")
    if attempts and len(builds) > 1:
        issues.append("qualification attempts do not share one build identity")
    if attempts and len(mversions) > 1:
        issues.append("qualification attempts do not share one Maestro version")
    return issues


def all_pass(attempts: list[Attempt], required: int) -> bool:
    return len(attempts) >= required and all(a.outcome == "pass" for a in attempts)


def update_evidence_file(evidence_path: pathlib.Path, flow_rel: str, record: dict) -> None:
    if evidence_path.exists():
        data = yaml.safe_load(evidence_path.read_text(encoding="utf-8")) or {}
        if not isinstance(data, dict):
            data = {}
    else:
        data = {}
    data.setdefault("schema", 1)
    data.setdefault("required_attempts", 3)
    quals = data.setdefault("qualifications", {})
    if not isinstance(quals, dict):
        quals = {}
        data["qualifications"] = quals
    quals[flow_rel] = record
    # remove stale exception for same flow? keep it - exception may be intentional; but qualification supersedes
    ex = data.get("exceptions")
    if isinstance(ex, dict) and flow_rel in ex:
        # keep; explicit exception still visible but qualification present
        pass
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    header = "# Maestro flow qualification evidence and exceptions.\n# A qualification requires required_attempts consecutive passing test attempts\n# in the same documented environment/build, with every attempt recorded.\n"
    evidence_path.write_text(header + yaml.safe_dump(data, sort_keys=False, allow_unicode=True), encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--flow", action="append", default=[], help="Flow path (repo-relative or absolute). Repeatable.")
    ap.add_argument("--admission-needed", action="store_true", help="Select flows in blocking scopes that are not admitted (need qualification/exception).")
    ap.add_argument("--list", action="store_true", help="List selected flows and exit without running.")
    ap.add_argument("--attempts", type=int, default=3, help="Number of consecutive attempts to run per flow (default: 3).")
    ap.add_argument("--serial", help="Device serial to pin.")
    ap.add_argument("--engine", choices=["maestro", "maestro-runner"], help="Maestro engine.")
    ap.add_argument("--skip-install", action="store_true", help="Skip APK install on each attempt.")
    ap.add_argument("--allow-stale-apk", action="store_true", help="Allow installed APK to differ from checkout.")
    ap.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT, help="Base directory for attempt artifacts.")
    ap.add_argument("--catalogue", type=pathlib.Path, default=DEFAULT_CATALOGUE)
    ap.add_argument("--baseline", type=pathlib.Path, default=DEFAULT_BASELINE)
    ap.add_argument("--initial", type=pathlib.Path, default=DEFAULT_INITIAL, help="The frozen set of flows that were in blocking scopes at rule introduction.")
    ap.add_argument("--evidence", type=pathlib.Path, default=DEFAULT_EVIDENCE)
    ap.add_argument("--record", action="store_true", help="Write qualification records for flows that qualify.")
    ap.add_argument("--no-write", action="store_true", help="With --record, print the record but do not update the evidence file.")
    ap.add_argument("--report", type=pathlib.Path, help="Write a JSON qualification report to this path.")
    ap.add_argument("extra", nargs=argparse.REMAINDER, help="Extra args after -- are passed to emulator.maestro.")
    args = ap.parse_args(argv)

    if args.attempts < 1:
        print("--attempts must be >= 1", file=sys.stderr)
        return 2

    try:
        required_by_policy, _, _ = GATE.read_evidence(args.evidence)
    except Exception as exc:
        print(f"cannot read evidence {args.evidence}: {exc}", file=sys.stderr)
        return 2
    if args.attempts < required_by_policy:
        print(
            f"--attempts {args.attempts} is below required_attempts={required_by_policy} in {args.evidence}",
            file=sys.stderr,
        )
        return 2

    flows = []
    for f in args.flow:
        p = pathlib.Path(f)
        if not p.is_absolute():
            p = (ROOT / p).resolve()
        try:
            rel = p.relative_to(ROOT).as_posix()
        except ValueError:
            print(f"flow must be inside the repository: {f}", file=sys.stderr)
            return 2
        flows.append(rel)

    if args.admission_needed:
        try:
            flows.extend(admission_needed(ROOT, args.catalogue, args.baseline, args.evidence, args.initial))
        except Exception as exc:
            print(f"cannot determine unadmitted flows: {exc}", file=sys.stderr)
            return 2

    # de-dup preserve order
    seen = set()
    uniq = []
    for f in flows:
        if f not in seen:
            seen.add(f)
            uniq.append(f)
    flows = uniq

    if not flows:
        print("no flows selected")
        return 0

    if args.list:
        for f in flows:
            print(f)
        return 0

    extra = list(args.extra)
    if extra[:1] == ["--"]:
        extra = extra[1:]
    # Qualification owns runner invariants (flow/junit-dir/engine/retries/cleanup, etc.).
    # Prefer an explicit allowlist over a denylist; no forwarded options are currently
    # supported to avoid overriding evidence/provenance or attempt isolation.
    allowed_extra_options: set[str] = set()
    for token in extra:
        name = token.split("=", 1)[0] if token.startswith("-") else token
        if token.startswith("-") and name in allowed_extra_options:
            continue
        if token.startswith("-"):
            print(
                f"extra argument {name} is not allowed (qualification runner controls "
                f"runner-owned options for evidence integrity)",
                file=sys.stderr,
            )
        else:
            print(
                f"extra argument {token!r} is not allowed (qualification runner does not "
                f"accept positional forwarded arguments)",
                file=sys.stderr,
            )
        return 2

    required = args.attempts
    all_results = []
    all_ok = True
    print(f"Qualifying {len(flows)} flow(s), {required} attempt(s) each")
    print(f"Artifacts: {args.out}")
    engine_used = effective_engine(args.engine)
    if maestro_version(engine_used) is None:
        # Said before the attempts, not after them: the run would spend three
        # attempts per flow on evidence the admission gate cannot accept. The
        # version is a prerequisite, not a note -- without it nothing the run
        # produces can be admitted.
        print(
            f"error: the {engine_used} version could not be detected — a qualification record "
            f"has to carry it, so nothing can be qualified",
            file=sys.stderr,
        )
        return 2

    for flow in flows:
        print(f"\n=== {flow} ===")
        # A flow that reads content the tree does not cover cannot be
        # qualified at all: the fingerprint says nothing about that content,
        # the snapshot the attempts run does not contain it, and an edit there
        # between two attempts would go unnoticed. Refusing before the
        # attempts saves three runs on evidence the gate cannot accept.
        untracked = GATE.dependency_issues(flow, ROOT)
        if untracked:
            issues = _untracked_issues(untracked)
            all_results.append(_refusal_summary(flow, issues))
            all_ok = False
            print("  REFUSED: the flow was not qualified (untracked dependency)")
            for issue in issues:
                print(f"  note: {issue}")
            continue
        # The attempts run against an immutable snapshot of the tree. A
        # fingerprint taken before and after the attempts would not catch a
        # flow that is edited and restored while the attempts are running:
        # the tree looks unchanged, and the record would claim three passing
        # runs for a version that only one attempt ever saw.
        snapshot_root, snapshot_flow_path = snapshot_flow(flow)
        try:
            # The snapshot is validated, not the tree: the copy is what the
            # attempts run, and a reference that leaves the tree can be
            # introduced between the check above and the copy. Validating the
            # snapshot itself closes that window — what is refused is what
            # the attempts would have read.
            snapshot_untracked = GATE.dependency_issues(flow, snapshot_root)
            if snapshot_untracked:
                issues = _untracked_issues(snapshot_untracked)
                all_results.append(_refusal_summary(flow, issues))
                all_ok = False
                print("  REFUSED: the flow was not qualified (untracked dependency in the snapshot)")
                for issue in issues:
                    print(f"  note: {issue}")
                continue
            # The fingerprint of the snapshot is the fingerprint of what the
            # attempts run; the fingerprint of the tree is the fingerprint of
            # what the record has to describe.
            fingerprint_ran = GATE.fingerprint(flow, snapshot_root)
            attempts = []
            for i in range(1, required + 1):
                attempts.append(
                    run_one_attempt(
                        flow,
                        i,
                        args.out,
                        args.serial,
                        args.engine,
                        args.skip_install,
                        args.allow_stale_apk,
                        extra,
                        flow_path=snapshot_flow_path,
                    )
                )
            fingerprint_tree = GATE.fingerprint(flow, ROOT)
            stable = fingerprint_ran == fingerprint_tree
            qualified = all_pass(attempts, required)
            record = build_qualification_record(flow, attempts, fingerprint_ran)
            issues = record_issues(record)
            if not stable:
                issues.insert(
                    0,
                    f"the flow changed while it was being qualified ({fingerprint_ran} -> "
                    f"{fingerprint_tree}); the attempts do not certify the current flow",
                )
            record["qualified"] = qualified and not issues
        finally:
            shutil.rmtree(snapshot_root, ignore_errors=True)
        summary = {
            "flow": flow,
            "qualified": qualified and not issues,
            "attempts_passed": qualified,
            "attempts_run": len(attempts),
            "outcomes": [a.outcome for a in attempts],
            "record_issues": issues,
            "record": record,
        }
        all_results.append(summary)
        if qualified and not issues:
            print(f"  QUALIFIED: {flow} ({required} consecutive passing attempts)")
            if args.record:
                if args.no_write:
                    print("  (record not written: --no-write)")
                    print(yaml.safe_dump({flow: {k: v for k, v in record.items() if k not in ('qualified',)}}, sort_keys=False))
                else:
                    update_evidence_file(args.evidence, flow, {k: v for k, v in record.items() if k not in ('qualified',)})
                    print(f"  recorded in {args.evidence}")
        elif qualified and issues:
            all_ok = False
            print(f"  NOT RECORDED AS QUALIFIED: {flow} ({required} consecutive passing attempts)")
            for issue in issues:
                print(f"  note: {issue}")
            print("  note: attempt artifacts are preserved; fix the issues above and re-run — a record is written only when every attempt carries the evidence the gate requires")
        else:
            all_ok = False
            outcomes = ", ".join(f"{a.attempt}:{a.outcome}" for a in attempts)
            print(f"  NOT QUALIFIED: {flow} ({outcomes})")
            if any(a.outcome == "test_failure" for a in attempts) and any(a.outcome == "pass" for a in attempts):
                print("  note: mixed pass/failure — flow is flaky, not qualified")
            if any(a.outcome == "infra_failure" for a in attempts):
                print("  note: infrastructure failures are not successful test attempts")

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "attempts_per_flow": required,
        "flows": all_results,
        "all_qualified": all_ok,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"\nReport: {args.report}")

    print(f"\nResult: {'all qualified' if all_ok else 'not all flows qualified'}")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
