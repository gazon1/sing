#!/usr/bin/env python3
"""maestro-allure.py — converts existing Maestro JUnit artifacts into Allure results.

The contract this implements is `docs/decisions/2026-11-10-maestro-allure-integration-contract.md`.
It is a proof of concept: it reads the artifacts a Maestro run already wrote and
writes Allure 2 result JSON next to a manifest. It does not run Maestro, does not
render, and does not publish.

Identity is the repo-relative flow path from `<testcase file="…">` — the only
path-shaped fact in the XML. `id`, `name` and `classname` carry the flow's
declared name, which is not unique. The JUnit XML is read through the
repository's one reader (`traceability.junit_xml`), tags through the one tag
reader (`scanners.maestro_flow`); there is no second parser.

Run-level facts (`--run-id`, `--run-attempt`, `--commit`, …) come from the
workflow, never from `environment.json`, and are cross-checked against it: an
identity that disagrees is a hard failure, not a merge. `--task-outcome auto`
reads the verdict from the results and the flows the run did not measure, so a
job that failed at an ancillary step does not describe a passing run as failed,
and a run that stopped early is not described as passing.

Usage:
    python3 scripts/maestro-allure.py --results DIR [--results DIR ...]
        --scope smoke|full --out DIR [--catalogue FILE]
        [--run-id ID] [--run-number N] [--run-attempt N] [--commit SHA]
        [--maestro-version V] [--apk-sha256 SHA] [--task-outcome OUTCOME]
        [--now ISO8601]

Exit codes:
    0 — the results were written. Exit 0 does NOT mean the run may be published:
        findings (missing flows, duplicates, missing evidence, incomplete
        identity) are carried in the manifest, and only
        `manifest.publishable == true` permits publication.
    1 — the converter could not do its job: a results directory or the
        catalogue is unreadable, the shards disagree about the run, or the
        output path is not a directory this tool wrote.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import itertools
import json
import os
import pathlib
import re
import sys
import uuid
import xml.etree.ElementTree as ET

# This checkout's packages, not an installed distribution's — the same rule
# every script that imports the package root follows (check-script-conventions).
_SRC = pathlib.Path(__file__).resolve().parent / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from scanners.maestro_flow import flow_tags_text  # noqa: E402
from traceability.junit_xml import TestCaseResult, parse_junit_file_strict  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CATALOGUE = ROOT / "config" / "docs" / "maestro-flows.txt"
MANIFEST_NAME = "maestro-allure-manifest.json"
SCHEMA_VERSION = 1
SCOPE_TAG = "smoke"
SMOKE = "smoke"
FULL = "full"

# The shared reader's vocabulary → Allure's. `error` is a failing outcome with a
# different meaning: the JUnit error element, not an assertion.
STATUS = {"passed": "passed", "failed": "failed", "error": "broken", "skipped": "skipped"}
FAILING = ("failed", "broken")

#: `git rev-parse --short` prints at least seven characters (the lower bound in
#: this pattern), and the runner's `_provenance.git_sha` writes this literal
#: when it cannot read the checkout.
GIT_SHA_RE = re.compile(r"[0-9a-f]{7,40}")
UNKNOWN_COMMIT = "unknown"

#: `--task-outcome auto`: the run's verdict is read from its own results.
AUTO = "auto"

#: Findings that remove a fact the report is built from. They are warnings for
#: the conversion — the results are still written — but a run carrying one is
#: not publishable: a report cannot say which flow was retried, or which flow was
#: never measured, if the artifact that says so is missing.
BLOCKING_FINDINGS = frozenset(
    {
        "run-summary.json missing",
        "run-summary.json unreadable",
        "environment.json missing",
        "environment.json unreadable",
        "unreadable report",
        "empty report",
        "flow unreadable",
        "flow file missing",
        "unknown flow",
        "duplicate testcase",
        "duplicate conflict",
        "unsupported status",
        "run-summary.json invalid",
        "summary flow ambiguous",
        "summary flow unknown",
        "environment.json incomplete",
    }
)

#: The fields `run-summary.json` must carry, all as lists of flow stems.
SUMMARY_FIELDS = ("passed", "failed", "not_measured", "retried")


def flow_identity(
    case: TestCaseResult, report: pathlib.Path, root: pathlib.Path, catalogue: list[str]
) -> str | None:
    """The repo-relative flow path for one testcase, or None when there is none.

    Maestro writes `file` repo-relative (measured). An absolute path is
    normalised against the root; a path outside the root is not a flow of this
    checkout and is refused. When `file` is absent the REPORT's stem is resolved
    through the catalogue — and only when exactly one flow carries it. The
    testcase's `classname` is the flow's declared name, not a path, so it cannot
    be used here.
    """
    raw = (case.file or "").strip()
    if raw:
        path = pathlib.Path(raw)
        if not path.is_absolute():
            path = root / path
        try:
            return path.resolve().relative_to(root.resolve()).as_posix()
        except ValueError:
            return None
    matches = [flow for flow in catalogue if pathlib.PurePosixPath(flow).stem == report.stem]
    return matches[0] if len(matches) == 1 else None


def flow_category(flow: str) -> str:
    """`Maestro/flows/tasks/01-create.yaml` → `tasks`; anything else → ''."""
    parts = pathlib.PurePosixPath(flow).parts
    return parts[2] if len(parts) >= 4 else ""


def flow_stem(flow: str) -> str:
    return pathlib.PurePosixPath(flow).stem


def flow_tags(path: pathlib.Path, findings: list[dict]) -> list[str]:
    """The flow's tags, or none when the file cannot be read. Never raises."""
    try:
        return flow_tags_text(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError) as exc:
        findings.append({"kind": "flow unreadable", "detail": f"{path}: {exc}"})
        return []


def summary_flows(shards: list[dict], findings: list[dict]) -> tuple[set[str], set[str]]:
    """The retried and not-measured flow stems, validated once.

    `run-summary.json` is required because it is the only record of retries and
    of flows that were never measured. A field that is not a list of strings is
    not "empty" — the fact is unavailable, and the run must not be published as
    if it had said "none".
    """
    retried: set[str] = set()
    not_measured: set[str] = set()
    for shard in shards:
        summary = shard["summary"]
        if not summary:
            continue  # read_shard already recorded why there is nothing to read
        invalid = [
            field
            for field in SUMMARY_FIELDS
            if not isinstance(summary.get(field), list)
            or any(not isinstance(item, str) for item in summary[field])
        ]
        if invalid:
            findings.append(
                {
                    "kind": "run-summary.json invalid",
                    "detail": f"shard {shard['label']}: {', '.join(invalid)} not a list of flow stems",
                }
            )
            continue
        retried.update(summary["retried"])
        not_measured.update(summary["not_measured"])
    return retried, not_measured


def resolve_stems(stems: set[str], catalogue: list[str], findings: list[dict], field: str) -> set[str]:
    """The flows a summary's stems name, refusing to guess when a stem is shared.

    The summary records a flow by its file stem, and two flows can share one.
    The testcase identity is the path, so the stem is resolved through the
    catalogue and only an unambiguous match is accepted.
    """
    by_stem: dict[str, list[str]] = {}
    for flow in catalogue:
        by_stem.setdefault(flow_stem(flow), []).append(flow)
    resolved: set[str] = set()
    for stem in sorted(stems):
        matches = by_stem.get(stem, [])
        if len(matches) == 1:
            resolved.add(matches[0])
            continue
        findings.append(
            {
                "kind": "summary flow ambiguous" if matches else "summary flow unknown",
                "detail": f"{field}: {stem!r} matches {len(matches)} catalogue flow(s)",
            }
        )
    return resolved


def duration_ms(case: TestCaseResult) -> int:
    """A testcase's duration, or 0 when the reader accepted a non-finite value.

    `float("NaN")` and `float("inf")` survive the shared reader's parse, and
    `int()` on either raises. A malformed duration is a finding about one field,
    not a reason to lose the whole run.
    """
    try:
        return max(int(case.time * 1000), 0)
    except (OverflowError, ValueError):
        return 0


def report_stop(report: pathlib.Path) -> int:
    """When the report was written — the fallback when it carries no timestamp."""
    try:
        return int(report.stat().st_mtime * 1000)
    except OSError:
        return 0


def case_stop(case: TestCaseResult, report: pathlib.Path, now_ms: int | None) -> int:
    """When the testcase ran. A downloaded artifact's mtime is when it was extracted.

    Maestro writes `<testcase timestamp>`; the shared reader now carries it. An
    mtime can be rewritten by upload, download and extraction, so it is the
    fallback, not the source.
    """
    if now_ms is not None:
        return now_ms
    if case.timestamp:
        try:
            return timestamp_ms(case.timestamp) or report_stop(report)
        except ValueError:
            pass
    return report_stop(report)


def read_catalogue(path: pathlib.Path) -> list[str]:
    entries = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            entries.append(stripped)
    return entries


def scope_flows(
    scope: str, catalogue: list[str], root: pathlib.Path, findings: list[dict]
) -> list[str]:
    """The flows of the catalogue this scope runs: the tag selection the runner makes.

    shortcut: this mirrors `emulator.maestro._flow.smoke_flows` (the `smoke` tag
    over the tree) over the catalogue instead of over the tree, so it can report
    `missing` flows the runner never selected. `check-maestro-flow-catalogue`
    keeps the two lists equal. The plan job publishes only the suite and the
    shard count, so there is no selected list to pass instead of this one.
    """
    if scope == FULL:
        return list(catalogue)
    selected = []
    for flow in catalogue:
        path = root / flow
        if not path.is_file():
            findings.append({"kind": "flow file missing", "detail": flow})
            continue
        if SCOPE_TAG in flow_tags(path, findings):
            selected.append(flow)
    return selected


def labels_for(flow: str, tags: list[str], scope: str, shard: int | None, retried: bool) -> list[dict]:
    """The Allure labels of one flow. Presentation and filtering, never identity."""
    labels = [
        {"name": "parentSuite", "value": "maestro"},
        {"name": "suite", "value": flow_category(flow)},
        {"name": "subSuite", "value": flow_stem(flow)},
        {"name": "framework", "value": "maestro"},
        {"name": "scope", "value": scope},
    ]
    labels += [{"name": "tag", "value": tag} for tag in tags]
    if shard is not None:
        labels.append({"name": "shard", "value": str(shard)})
    if retried:
        # The runner deletes the previous attempt's report, so the XML is the
        # terminal attempt: the fact that a retry happened is a label, never an
        # Allure retry that the artifacts cannot support.
        labels.append({"name": "retried", "value": "true"})
    return labels


def identifiers(flow: str, run_id: str | None, run_attempt: int | None) -> tuple[str, str, str]:
    """(testCaseId, historyId, uuid) — deterministic, so a re-conversion is stable."""
    test_case_id = "maestro:" + flow
    history_id = hashlib.md5(test_case_id.encode("utf-8")).hexdigest()
    seed = f"{test_case_id}@{run_id or 'local'}.{run_attempt or 1}"
    return test_case_id, history_id, str(uuid.uuid5(uuid.NAMESPACE_URL, seed))


def status_of(case: TestCaseResult, flow: str, findings: list[dict]) -> str | None:
    """The Allure status, or None when the JUnit outcome is not one this contract maps."""
    status = STATUS.get(case.status)
    if status is None:
        findings.append(
            {
                "kind": "unsupported status",
                "detail": f"{flow}: {case.status!r} has no Allure mapping; the testcase is not reported",
            }
        )
    return status


def result_for(
    case: TestCaseResult,
    flow: str,
    tags: list[str],
    scope: str,
    shard: int | None,
    retried: bool,
    stop_ms: int,
    run_id: str | None,
    run_attempt: int | None,
    status: str,
) -> dict:
    """One Allure 2 result. The failure text is the run's own, never a fabrication."""
    test_case_id, history_id, result_uuid = identifiers(flow, run_id, run_attempt)
    details: dict[str, str] = {}
    if status in FAILING:
        message = case.message or next(
            (line.strip() for line in case.stacktrace.splitlines() if line.strip()), ""
        )
        details = {"message": message}
        if case.stacktrace:
            details["trace"] = case.stacktrace
    return {
        "uuid": result_uuid,
        "historyId": history_id,
        "testCaseId": test_case_id,
        "fullName": flow,
        "name": case.name or flow_stem(flow),
        "status": status,
        "statusDetails": details,
        "stage": "finished",
        "start": stop_ms - duration_ms(case),
        "stop": stop_ms,
        "labels": labels_for(flow, tags, scope, shard, retried),
        "links": [],
        "steps": [],
    }


def read_shard(directory: pathlib.Path, findings: list[dict]) -> dict:
    """One shard's artifacts. A malformed file is a finding, never an exception."""
    shard: dict = {"label": directory.name, "environment": {}, "summary": {}, "cases": []}
    for name, key in (("environment.json", "environment"), ("run-summary.json", "summary")):
        path = directory / name
        if not path.is_file():
            findings.append({"kind": f"{name} missing", "detail": str(directory / name)})
            continue
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            findings.append({"kind": f"{name} unreadable", "detail": f"{path}: {exc}"})
            continue
        if not isinstance(value, dict):
            findings.append({"kind": f"{name} unreadable", "detail": f"{path}: not an object"})
            continue
        shard[key] = value
    for xml in sorted(directory.glob("*.xml")):
        try:
            cases = parse_junit_file_strict(xml)
        except (ET.ParseError, OSError) as exc:
            findings.append({"kind": "unreadable report", "detail": f"{xml}: {exc}"})
            continue
        if not cases:
            findings.append({"kind": "empty report", "detail": f"{xml}: no testcase"})
        shard["cases"].append((xml, cases))
    return shard


def convert(
    shards: list[dict], root: pathlib.Path, meta: dict, now_ms: int | None, retried: set[str]
) -> dict:
    """Results by flow identity, with the duplicate rule that never loses a failure."""
    results: dict[str, dict] = {}
    findings: list[dict] = []
    catalogue = meta["catalogue"]
    first_report: dict[str, pathlib.Path] = {}

    for shard in shards:
        shard_no = shard["environment"].get("shard")
        shard_no = shard_no if isinstance(shard_no, int) and not isinstance(shard_no, bool) else None
        for xml, cases in shard["cases"]:
            for case in cases:
                flow = flow_identity(case, xml, root, catalogue)
                if flow is None:
                    findings.append(
                        {"kind": "unknown flow", "detail": f"{xml}: {case.file or case.name} is not a flow of this checkout"}
                    )
                    continue
                status = status_of(case, flow, findings)
                if status is None:
                    continue
                flow_file = root / flow
                if flow_file.is_file():
                    tags = flow_tags(flow_file, findings)
                else:
                    tags = []
                    findings.append({"kind": "flow file missing", "detail": flow})
                result = result_for(
                    case, flow, tags, meta["scope"], shard_no, flow in retried,
                    case_stop(case, xml, now_ms), meta["run_id"], meta["run_attempt"], status,
                )
                existing = results.get(result["testCaseId"])
                if existing is None:
                    results[result["testCaseId"]] = result
                    first_report[result["testCaseId"]] = xml
                    continue
                findings.append(
                    {
                        "kind": "duplicate testcase",
                        "detail": f"{flow}: reported by {first_report[result['testCaseId']]} and {xml}",
                    }
                )
                if existing["status"] != result["status"]:
                    findings.append(
                        {
                            "kind": "duplicate conflict",
                            "detail": f"{flow}: {existing['status']} and {result['status']} — the failure is kept",
                        }
                    )
                # A pass never replaces a failure. The wrong response to a
                # duplicate is to let whichever file was read second decide.
                if existing["status"] not in FAILING and result["status"] in FAILING:
                    results[result["testCaseId"]] = result
    return {"results": results, "findings": findings}


def same_commit(left: object, right: object) -> bool:
    """Whether two recorded commits are the same one.

    The runner records `git rev-parse --short=12` and the workflow carries the
    full `github.sha`: an abbreviation of the same commit is not a disagreement.
    Only a real abbreviation counts — `git rev-parse` never prints fewer than
    seven characters, so a three-character prefix is a different string, not a
    commit — and anything that is not a string is not a commit at all, so `123`
    and `"123"` stay different values.
    """
    if isinstance(left, str) and isinstance(right, str):
        if left == right:
            return True
        shorter, longer = sorted((left, right), key=len)
        return (
            GIT_SHA_RE.fullmatch(shorter) is not None
            and GIT_SHA_RE.fullmatch(longer) is not None
            and longer.startswith(shorter)
        )
    return json.dumps(left, sort_keys=True) == json.dumps(right, sort_keys=True)


def recorded_commit(shard: dict) -> object | None:
    """What a shard says the commit was, or None when it says it could not tell.

    `_provenance.git_sha` writes the literal `unknown` when git is not there.
    That is neither a commit nor a disagreement: the shard is incomplete, which
    the check below reports as unpublishable rather than fatal.
    """
    value = shard["environment"].get("commit")
    return None if not value or value == UNKNOWN_COMMIT else value


def shard_identity(shards: list[dict], meta: dict) -> tuple[bool, list[dict]]:
    """Whether every shard agrees about the run, and whether each one says what it is.

    Disagreement is fatal: the shards are not the same run. A missing `commit`
    is not fatal — the conversion can still describe the run — but it is a fact
    the ADR requires a cross-check for, so the run is not publishable.
    """
    findings: list[dict] = []
    raw_commits = [commit for commit in map(recorded_commit, shards) if commit]
    # Every pair, not every value against the first: prefix equality is not
    # transitive — `abcdef0` agrees with both `abcdef01` and `abcdef02`, which
    # disagree with each other — so anchoring on one shard can miss a split.
    if any(not same_commit(one, other) for one, other in itertools.combinations(raw_commits, 2)):
        findings.append(
            {
                "kind": "identity disagreement",
                "detail": f"shards carry different commits: {sorted(str(c) for c in raw_commits)}",
            }
        )
    if meta["commit"] and raw_commits and any(
        not same_commit(meta["commit"], value) for value in raw_commits
    ):
        findings.append(
            {
                "kind": "identity disagreement",
                "detail": f"the workflow says {meta['commit']}, the shards say {sorted(str(c) for c in raw_commits)}",
            }
        )
    fatal = bool(findings)
    for shard in shards:
        if recorded_commit(shard) is None:
            findings.append(
                {
                    "kind": "environment.json incomplete",
                    "detail": f"shard {shard['label']} has no commit; the run cannot be tied to a checkout",
                }
            )
    return fatal, findings


def shard_set(shards: list[dict]) -> tuple[list[int], int | None, bool, list[dict]]:
    """The shard numbers observed, the count expected, completeness, and why not."""
    findings: list[dict] = []
    observed = sorted(
        {
            shard["environment"]["shard"]
            for shard in shards
            if isinstance(shard["environment"].get("shard"), int)
            and not isinstance(shard["environment"]["shard"], bool)
        }
    )
    counts = {
        shard["environment"]["shards"]
        for shard in shards
        if isinstance(shard["environment"].get("shards"), int)
        and not isinstance(shard["environment"]["shards"], bool)
    }
    if len(counts) > 1:
        findings.append(
            {"kind": "shard count disagreement", "detail": f"shards report different totals: {sorted(counts)}"}
        )
    expected = next(iter(counts)) if len(counts) == 1 else None
    if expected is None and not counts:
        findings.append({"kind": "shard count unknown", "detail": "no shard count in environment.json"})
    if expected is not None and expected < 1:
        findings.append(
            {"kind": "shard count invalid", "detail": f"environment.json says shards={expected}"}
        )
        expected = None
    complete = expected is not None and observed == list(range(1, expected + 1))
    if expected is not None and not complete:
        findings.append(
            {"kind": "incomplete shard set", "detail": f"observed shards {observed}, expected 1..{expected}"}
        )
    return observed, expected, complete, findings


def derived_outcome(results: dict[str, dict], unmeasured: list[str]) -> str:
    """The run's verdict from what it measured, not from the job that carried it.

    A job can fail at an ancillary step — the traceability export, an upload —
    while every flow passed, and the shard's evidence index reports the flows'
    own outcome (`steps.maestro.outcome`). A clean set of results is not a pass
    either: a run that stopped early has fewer results, not failing ones, and
    the flows its scope expected and no report covers are the fact that says so
    (the gate and the index both fail the run for them).
    """
    if any(result["status"] in FAILING for result in results.values()):
        return "failed"
    if unmeasured:
        return "failed"
    return "passed" if results else "unknown"


def manifest_for(
    shards: list[dict],
    conversion: dict,
    meta: dict,
    expected_flows: list[str],
    not_measured: list[str],
    findings: list[dict],
) -> dict:
    results = conversion["results"]
    counts: dict[str, int] = {}
    for result in results.values():
        counts[result["status"]] = counts.get(result["status"], 0) + 1
    produced = set(results)
    missing = [flow for flow in expected_flows if "maestro:" + flow not in produced]
    observed, expected_shards, complete, shard_findings = shard_set(shards)
    findings = list(findings) + shard_findings
    identity_known = all(
        meta[key]
        for key in (
            "run_id", "run_number", "run_attempt", "commit", "maestro_version", "apk_sha256"
        )
    )
    publishable = bool(
        results
        and identity_known
        and complete
        and meta["task_outcome"] in ("passed", "failed")
        and not any(finding["kind"] in BLOCKING_FINDINGS for finding in findings)
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "scope": meta["scope"],
        "identity": {
            "run_id": meta["run_id"],
            "run_number": meta["run_number"],
            "run_attempt": meta["run_attempt"],
            "commit_sha": meta["commit"],
            "maestro_version": meta["maestro_version"],
            "apk_sha256": meta["apk_sha256"],
        },
        "isolation_key": "run_id + run_attempt + commit_sha + scope",
        "shards": observed,
        "shards_expected": expected_shards,
        "complete_shards": complete,
        "task_outcome": meta["task_outcome"],
        "counts": counts,
        "flows": [
            {
                "test_case_id": result["testCaseId"],
                "flow": result["fullName"],
                "status": result["status"],
            }
            for result in sorted(results.values(), key=lambda r: r["fullName"])
        ],
        "missing": sorted(missing),
        "not_measured": not_measured,
        "publishable": publishable,
        "findings": findings,
    }


def executor_for(meta: dict) -> dict:
    executor = {"name": f"Maestro ({meta['scope']})", "type": "github" if meta["run_id"] else "local"}
    if meta["run_id"]:
        executor["buildName"] = f"{meta['run_id']}.{meta['run_attempt'] or 1}"
        server = os.environ.get("GITHUB_SERVER_URL")
        repo = os.environ.get("GITHUB_REPOSITORY")
        if server and repo:
            executor["buildUrl"] = f"{server}/{repo}/actions/runs/{meta['run_id']}"
    return executor


def is_ours(out: pathlib.Path) -> bool:
    """Whether the results in a directory are this tool's own, or there are none.

    A result of ours carries a Maestro testcase id. Anything else is somebody's
    evidence — the JVM tier's, when `--out` points at the merged results — and
    cleaning it would destroy the input of a published report. This is also what
    makes an interrupted conversion recoverable: its files are recognisably ours
    even when the manifest was never written.
    """
    if (out / MANIFEST_NAME).is_file():
        return True
    results = list(out.glob("*-result.json"))
    if results:
        for path in results:
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
                if not str(payload.get("testCaseId", "")).startswith("maestro:"):
                    return False
            except (OSError, ValueError, AttributeError):
                return False
        return True
    # No results and no manifest: our own interrupted run never gets here, and a
    # directory holding another producer's executor.json is not ours to clean.
    return not (out / "executor.json").exists()


def clean(out: pathlib.Path) -> None:
    """Drop a previous conversion. Call only after is_ours(out) said yes."""
    out.mkdir(parents=True, exist_ok=True)
    for path in (
        list(out.glob("*-result.json")) + list(out.glob("*.tmp")) + [out / "executor.json", out / MANIFEST_NAME]
    ):
        if path.is_file():
            path.unlink()


def write_json(path: pathlib.Path, payload: dict) -> None:
    """Write through a temporary sibling, so a crash never leaves a truncated file."""
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def timestamp_ms(value: str | None) -> int | None:
    if not value:
        return None
    text = value.replace("Z", "+00:00")
    parsed = datetime.datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=datetime.timezone.utc)
    return int(parsed.timestamp() * 1000)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--results", action="append", required=True, help="A shard's results directory (repeatable).")
    parser.add_argument("--scope", choices=(SMOKE, FULL), required=True)
    parser.add_argument("--out", required=True, help="Where the Allure results are written.")
    parser.add_argument("--root", default=str(ROOT), help="The checkout the run measured.")
    parser.add_argument("--catalogue", default=str(DEFAULT_CATALOGUE))
    parser.add_argument("--run-id")
    parser.add_argument("--run-number", type=int)
    parser.add_argument("--run-attempt", type=int)
    parser.add_argument("--commit", help="github.sha of the run.")
    parser.add_argument("--maestro-version")
    parser.add_argument("--apk-sha256")
    parser.add_argument(
        "--task-outcome",
        default="unknown",
        choices=(AUTO, "passed", "failed", "skipped", "cancelled", "timed_out", "unknown"),
        help="The run's verdict. `auto` reads it from the results.",
    )
    parser.add_argument("--now", help="Pin start/stop to this ISO8601 instant (deterministic output).")
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    try:
        catalogue = read_catalogue(pathlib.Path(args.catalogue))
    except (OSError, UnicodeDecodeError) as exc:
        print(f"maestro-allure: cannot read the catalogue: {exc}", file=sys.stderr)
        return 1

    missing_dirs = [d for d in args.results if not pathlib.Path(d).is_dir()]
    if missing_dirs:
        print(f"maestro-allure: results directories do not exist: {', '.join(missing_dirs)}", file=sys.stderr)
        return 1

    findings: list[dict] = []
    shards = [read_shard(pathlib.Path(d), findings) for d in args.results]

    meta = {
        "catalogue": catalogue,
        "scope": args.scope,
        "run_id": args.run_id,
        "run_number": args.run_number,
        "run_attempt": args.run_attempt,
        "commit": args.commit,
        "maestro_version": args.maestro_version or None,
        "apk_sha256": args.apk_sha256 or None,
        "task_outcome": args.task_outcome,
    }
    fatal, identity_findings = shard_identity(shards, meta)
    findings += identity_findings
    if fatal:
        for finding in identity_findings:
            print(f"maestro-allure: {finding['kind']}: {finding['detail']}", file=sys.stderr)
        return 1

    out = pathlib.Path(args.out)
    if (out.exists() or out.is_symlink()) and not out.is_dir():
        print(f"maestro-allure: {out} is not a directory", file=sys.stderr)
        return 1
    if out.is_dir() and not is_ours(out):
        print(
            f"maestro-allure: {out} holds Allure results this tool did not write; "
            f"refusing to clean it",
            file=sys.stderr,
        )
        return 1

    if args.now is not None and not args.now.strip():
        print("maestro-allure: --now is empty; omit it or pass an ISO8601 instant", file=sys.stderr)
        return 1
    try:
        now_ms = timestamp_ms(args.now)
    except ValueError as exc:
        print(f"maestro-allure: --now is not an ISO8601 instant: {exc}", file=sys.stderr)
        return 1

    retried_stems, unmeasured_stems = summary_flows(shards, findings)
    retried = resolve_stems(retried_stems, catalogue, findings, "retried")
    not_measured = sorted(resolve_stems(unmeasured_stems, catalogue, findings, "not_measured"))

    expected_flows = scope_flows(meta["scope"], catalogue, root, findings)
    conversion = convert(shards, root, meta, now_ms, retried)
    if meta["task_outcome"] == AUTO:
        produced = {result["fullName"] for result in conversion["results"].values()}
        meta["task_outcome"] = derived_outcome(
            conversion["results"],
            [flow for flow in expected_flows if flow not in produced] + not_measured,
        )
    findings = findings + conversion["findings"]
    manifest = manifest_for(shards, conversion, meta, expected_flows, not_measured, findings)

    try:
        clean(out)
        for result in conversion["results"].values():
            write_json(out / f"{result['uuid']}-result.json", result)
        write_json(out / "executor.json", executor_for(meta))
        write_json(out / MANIFEST_NAME, manifest)
    except OSError as exc:
        print(f"maestro-allure: cannot write the results to {out}: {exc}", file=sys.stderr)
        return 1

    for finding in manifest["findings"]:
        print(f"maestro-allure: {finding['kind']}: {finding['detail']}", file=sys.stderr)
    print(
        f"maestro-allure: {len(conversion['results'])} result(s), "
        f"{len(manifest['missing'])} missing, {len(manifest['findings'])} finding(s) — {out}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
