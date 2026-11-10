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
identity that disagrees is a hard failure, not a merge.

Usage:
    python3 scripts/maestro-allure.py --results DIR [--results DIR ...]
        --scope smoke|full --out DIR [--catalogue FILE]
        [--run-id ID] [--run-number N] [--run-attempt N] [--commit SHA]
        [--maestro-version V] [--apk-sha256 SHA] [--task-outcome OUTCOME]
        [--now ISO8601]

Exit codes:
    0 — the results were written; findings (missing flows, duplicates, missing
        evidence) are warnings, carried in the manifest
    1 — the converter could not do its job: a results directory or the
        catalogue is unreadable, or the shards disagree about the run
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import pathlib
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
        "duplicate conflict",
    }
)


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
        if path.is_absolute():
            try:
                path = path.resolve().relative_to(root.resolve())
            except ValueError:
                return None
        return path.as_posix()
    matches = [flow for flow in catalogue if pathlib.PurePosixPath(flow).stem == report.stem]
    return matches[0] if len(matches) == 1 else None


def flow_category(flow: str) -> str:
    """`Maestro/flows/tasks/01-create.yaml` → `tasks`; anything else → ''."""
    parts = pathlib.PurePosixPath(flow).parts
    return parts[2] if len(parts) >= 4 else ""


def flow_stem(flow: str) -> str:
    return pathlib.PurePosixPath(flow).stem


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
    """The flows of the catalogue this scope runs: the tag selection the runner makes."""
    if scope == FULL:
        return list(catalogue)
    selected = []
    for flow in catalogue:
        path = root / flow
        if not path.is_file():
            findings.append({"kind": "flow file missing", "detail": flow})
            continue
        if SCOPE_TAG in flow_tags_text(path.read_text(encoding="utf-8")):
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
) -> dict:
    """One Allure 2 result. The failure text is the run's own, never a fabrication."""
    test_case_id, history_id, result_uuid = identifiers(flow, run_id, run_attempt)
    status = STATUS.get(case.status, "unknown")
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
        "start": stop_ms - int(case.time * 1000),
        "stop": stop_ms,
        "labels": labels_for(flow, tags, scope, shard, retried),
        "links": [],
        "steps": [],
    }


def read_shard(directory: pathlib.Path, findings: list[dict]) -> dict:
    """One shard's artifacts. A malformed file is a finding, never an exception."""
    shard: dict = {"environment": {}, "summary": {}, "cases": []}
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


def convert(shards: list[dict], root: pathlib.Path, meta: dict, stop_ms: int) -> dict:
    """Results by flow identity, with the duplicate rule that never loses a failure."""
    results: dict[str, dict] = {}
    findings: list[dict] = []
    retried = {stem for shard in shards for stem in shard["summary"].get("retried", [])}
    catalogue = meta["catalogue"]

    for shard in shards:
        shard_no = shard["environment"].get("shard")
        shard_no = shard_no if isinstance(shard_no, int) else None
        for xml, cases in shard["cases"]:
            for case in cases:
                flow = flow_identity(case, xml, root, catalogue)
                if flow is None:
                    findings.append(
                        {"kind": "unknown flow", "detail": f"{xml}: {case.file or case.name} is not a flow of this checkout"}
                    )
                    continue
                flow_file = root / flow
                if flow_file.is_file():
                    tags = flow_tags_text(flow_file.read_text(encoding="utf-8"))
                else:
                    tags = []
                    findings.append({"kind": "flow file missing", "detail": flow})
                result = result_for(
                    case, flow, tags, meta["scope"], shard_no,
                    flow_stem(flow) in retried, stop_ms, meta["run_id"], meta["run_attempt"],
                )
                existing = results.get(result["testCaseId"])
                if existing is None:
                    results[result["testCaseId"]] = result
                    continue
                findings.append(
                    {"kind": "duplicate testcase", "detail": f"{flow} appears in {xml} and in another shard"}
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


def shard_identity(shards: list[dict], meta: dict) -> tuple[bool, list[dict]]:
    """Whether every shard agrees about the run. Disagreement is fatal."""
    findings: list[dict] = []
    commits = {shard["environment"].get("commit") for shard in shards if shard["environment"].get("commit")}
    if len(commits) > 1:
        findings.append({"kind": "identity disagreement", "detail": f"shards carry different commits: {sorted(commits)}"})
    if meta["commit"] and commits and commits != {meta["commit"]}:
        findings.append(
            {
                "kind": "identity disagreement",
                "detail": f"the workflow says {meta['commit']}, the shards say {sorted(commits)}",
            }
        )
    return (not findings), findings


def shard_set(shards: list[dict]) -> tuple[list[int], int | None, bool]:
    """The shard numbers observed, the count expected, and whether the set is complete."""
    observed = sorted(
        {shard["environment"]["shard"] for shard in shards if isinstance(shard["environment"].get("shard"), int)}
    )
    counts = {shard["environment"].get("shards") for shard in shards if isinstance(shard["environment"].get("shards"), int)}
    expected = counts.pop() if len(counts) == 1 else None
    complete = expected is not None and observed == list(range(1, expected + 1))
    return observed, expected, complete


def manifest_for(
    shards: list[dict], conversion: dict, meta: dict, expected_flows: list[str], findings: list[dict]
) -> dict:
    results = conversion["results"]
    counts: dict[str, int] = {}
    for result in results.values():
        counts[result["status"]] = counts.get(result["status"], 0) + 1
    produced = set(results)
    missing = [flow for flow in expected_flows if "maestro:" + flow not in produced]
    not_measured = sorted({stem for shard in shards for stem in shard["summary"].get("not_measured", [])})
    observed, expected_shards, complete = shard_set(shards)
    identity_known = all(meta[key] for key in ("run_id", "run_number", "run_attempt", "commit"))
    publishable = bool(
        identity_known
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


def reset(out: pathlib.Path) -> bool:
    """Drop a previous conversion; refuse a directory that is not this tool's.

    A stale result is evidence about another run, so a re-conversion cleans the
    directory first. It may only clean a directory it wrote: a `*-result.json`
    without our manifest is somebody else's evidence — the JVM tier's, when
    `--out` is pointed at `build/allure-results-merged` — and deleting it would
    destroy the input of the published report.
    """
    if out.is_dir() and list(out.glob("*-result.json")) and not (out / MANIFEST_NAME).is_file():
        return False
    out.mkdir(parents=True, exist_ok=True)
    for path in list(out.glob("*-result.json")) + [out / "executor.json", out / MANIFEST_NAME]:
        if path.is_file():
            path.unlink()
    return True


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
        choices=("passed", "failed", "skipped", "cancelled", "timed_out", "unknown"),
    )
    parser.add_argument("--now", help="Pin start/stop to this ISO8601 instant (deterministic output).")
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    try:
        catalogue = read_catalogue(pathlib.Path(args.catalogue))
    except OSError as exc:
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
        "maestro_version": args.maestro_version,
        "apk_sha256": args.apk_sha256,
        "task_outcome": args.task_outcome,
    }
    agreed, identity_findings = shard_identity(shards, meta)
    if not agreed:
        for finding in identity_findings:
            print(f"maestro-allure: {finding['kind']}: {finding['detail']}", file=sys.stderr)
        return 1

    out = pathlib.Path(args.out)
    if not reset(out):
        print(
            f"maestro-allure: {out} holds Allure results this tool did not write; "
            f"refusing to clean it",
            file=sys.stderr,
        )
        return 1

    stop_ms = timestamp_ms(args.now)
    if stop_ms is None:
        stop_ms = max(
            (int(xml.stat().st_mtime * 1000) for shard in shards for xml, _ in shard["cases"]),
            default=0,
        )
    expected_flows = scope_flows(meta["scope"], catalogue, root, findings)
    conversion = convert(shards, root, meta, stop_ms)
    findings = findings + conversion["findings"]
    manifest = manifest_for(shards, conversion, meta, expected_flows, findings)

    for result in conversion["results"].values():
        (out / f"{result['uuid']}-result.json").write_text(
            json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
    (out / "executor.json").write_text(
        json.dumps(executor_for(meta), indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (out / MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    for finding in findings:
        print(f"maestro-allure: {finding['kind']}: {finding['detail']}", file=sys.stderr)
    print(
        f"maestro-allure: {len(conversion['results'])} result(s), "
        f"{len(manifest['missing'])} missing, {len(findings)} finding(s) — {out}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
