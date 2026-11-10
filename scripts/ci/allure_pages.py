#!/usr/bin/env python3
"""Allure Pages publication state: manifest, latest selection, and validation.

The publication job uses this helper to decide whether an incoming run may update
GitHub Pages state and to apply that decision without regressing a newer/latest
publication. Ordering is by (run_number, run_attempt); idempotency is by
(run_id, run_attempt), per docs/decisions/allure-publication-latest-history-and-retention.md
and #2764.

This module is dependency-light and testable without GitHub/network access.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1


class PublishError(Exception):
    """A clear publication failure that must not delete the current site."""


@dataclass(frozen=True)
class Publication:
    run_id: int
    run_number: int
    run_attempt: int
    event: str
    sha: str
    population: str
    report_path: str

    @classmethod
    def from_args(cls, args: argparse.Namespace) -> "Publication":
        return cls(
            run_id=_positive_int(args.run_id, "run-id"),
            run_number=_positive_int(args.run_number, "run-number"),
            run_attempt=_positive_int(args.run_attempt, "run-attempt"),
            event=_non_empty(args.event, "event"),
            sha=_non_empty(args.sha, "sha"),
            population=_non_empty(args.population, "population"),
            report_path=_non_empty(args.report_path, "report-path"),
        )

    @property
    def identity(self) -> str:
        return f"{self.run_id}-{self.run_attempt}"

    @property
    def order_key(self) -> tuple[int, int]:
        return (self.run_number, self.run_attempt)

    def to_dict(self) -> dict[str, Any]:
        return {
            "run_id": self.run_id,
            "run_number": self.run_number,
            "run_attempt": self.run_attempt,
            "event": self.event,
            "sha": self.sha,
            "population": self.population,
            "report_path": self.report_path,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any], label: str) -> "Publication":
        if not isinstance(data, dict):
            raise PublishError(f"malformed {label}: expected object")
        try:
            return cls(
                run_id=_positive_int(data.get("run_id"), f"{label}.run_id"),
                run_number=_positive_int(data.get("run_number"), f"{label}.run_number"),
                run_attempt=_positive_int(data.get("run_attempt"), f"{label}.run_attempt"),
                event=_non_empty(data.get("event"), f"{label}.event"),
                sha=_non_empty(data.get("sha"), f"{label}.sha"),
                population=_non_empty(data.get("population"), f"{label}.population"),
                report_path=_non_empty(data.get("report_path"), f"{label}.report_path"),
            )
        except PublishError:
            raise
        except Exception as exc:  # pragma: no cover - defensive
            raise PublishError(f"malformed {label}: {exc}") from exc


def _positive_int(value: Any, name: str) -> int:
    if isinstance(value, bool):
        raise PublishError(f"invalid {name}: expected positive integer")
    try:
        number = int(value)
    except Exception as exc:
        raise PublishError(f"invalid {name}: expected positive integer") from exc
    if number < 1:
        raise PublishError(f"invalid {name}: expected >= 1")
    return number


def _non_empty(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PublishError(f"invalid {name}: expected non-empty string")
    return value


def _manifest_path(site_dir: Path) -> Path:
    return site_dir / "allure-manifest.json"


def load_manifest(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"schema_version": SCHEMA_VERSION, "latest": None, "publications": {}}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise PublishError(f"malformed publication manifest: {exc}") from exc
    if not isinstance(data, dict):
        raise PublishError("malformed publication manifest: expected object")
    version = data.get("schema_version")
    if version != SCHEMA_VERSION:
        raise PublishError(f"unsupported publication manifest schema_version: {version!r}")
    publications = data.get("publications")
    latest = data.get("latest", None)
    if not isinstance(publications, dict):
        raise PublishError("malformed publication manifest: publications must be an object")
    if latest is not None and not isinstance(latest, dict):
        raise PublishError("malformed publication manifest: latest must be an object or null")
    normalized: dict[str, Any] = {}
    for key, value in publications.items():
        pub = Publication.from_dict(value, f"publications[{key!r}]")
        if key != pub.identity:
            raise PublishError(
                f"malformed publication manifest: identity key {key!r} does not match {pub.identity!r}"
            )
        normalized[key] = pub.to_dict()
    latest_norm = None
    if latest is not None:
        latest_pub = Publication.from_dict(latest, "latest")
        if latest_pub.identity not in normalized:
            raise PublishError("malformed publication manifest: latest is not present in publications")
        latest_norm = normalized[latest_pub.identity]
    return {"schema_version": SCHEMA_VERSION, "latest": latest_norm, "publications": normalized}


def save_manifest(path: Path, manifest: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def decide(manifest: dict[str, Any], incoming: Publication) -> tuple[str, str]:
    publications = manifest["publications"]
    existing = publications.get(incoming.identity)
    latest = manifest.get("latest")
    latest_key = None
    if latest is not None:
        latest_key = Publication.from_dict(latest, "latest").order_key

    if existing is not None:
        existing_pub = Publication.from_dict(existing, "existing publication")
        if existing_pub.to_dict() != incoming.to_dict():
            raise PublishError(
                f"publication identity {incoming.identity} already recorded with different metadata"
            )
        if latest is not None and Publication.from_dict(latest, "latest").identity == incoming.identity:
            return "skip-duplicate", f"{incoming.identity} is already the recorded latest publication"
        return "skip-duplicate", f"{incoming.identity} is already recorded"

    if latest_key is not None and incoming.order_key <= latest_key:
        return "skip-stale", (
            f"incoming {list(incoming.order_key)} is not newer than latest {list(latest_key)}"
        )
    return "publish", "incoming publication wins latest selection"


def write_root_index(site_dir: Path, report_path: str) -> None:
    target = f"{report_path}/index.html"
    html = (
        '<!DOCTYPE html><meta charset="utf-8">'
        f'<meta http-equiv="refresh" content="0; URL={target}">'
        '<meta http-equiv="Pragma" content="no-cache">'
        '<meta http-equiv="Expires" content="0">\n'
    )
    (site_dir / "index.html").write_text(html, encoding="utf-8")


def validate_manifest_state(site_dir: Path, manifest: dict[str, Any]) -> None:
    latest = manifest.get("latest")
    if latest is None:
        raise PublishError("publication manifest has no latest entry")
    latest_pub = Publication.from_dict(latest, "latest")
    report_dir = site_dir / latest_pub.report_path
    if not report_dir.is_dir():
        raise PublishError(f"manifest latest points to missing report: {latest_pub.report_path}")
    if not (report_dir / "index.html").is_file():
        raise PublishError(f"manifest latest report is missing index.html: {latest_pub.report_path}")
    root_index = site_dir / "index.html"
    if not root_index.is_file():
        raise PublishError("generated root index.html is missing")
    content = root_index.read_text(encoding="utf-8")
    expected = f"URL={latest_pub.report_path}/index.html"
    if expected not in content:
        raise PublishError(
            f"root index.html does not point to manifest latest report: expected {expected!r}"
        )


def validate_pages_input(results_dir: Path) -> None:
    if not results_dir.exists():
        raise PublishError(f"missing Pages input artifact directory: {results_dir}")
    if not results_dir.is_dir():
        raise PublishError(f"Pages input artifact is not a directory: {results_dir}")
    if not any(results_dir.rglob("*-result.json")):
        raise PublishError(
            f"malformed Pages input artifact: no *-result.json files in {results_dir}"
        )


def apply_publication(site_dir: Path, manifest_path: Path, incoming: Publication) -> dict[str, Any]:
    manifest = load_manifest(manifest_path)
    action, reason = decide(manifest, incoming)
    publications = dict(manifest["publications"])

    if action == "publish":
        report_dir = site_dir / incoming.report_path
        if not report_dir.is_dir():
            raise PublishError(f"latest report target does not exist: {incoming.report_path}")
        if not (report_dir / "index.html").is_file():
            raise PublishError(f"latest report target is missing index.html: {incoming.report_path}")
        publications[incoming.identity] = incoming.to_dict()
        manifest = {
            "schema_version": SCHEMA_VERSION,
            "latest": incoming.to_dict(),
            "publications": publications,
        }
        write_root_index(site_dir, incoming.report_path)
        save_manifest(manifest_path, manifest)
        validate_manifest_state(site_dir, manifest)
        return {"action": action, "reason": reason, "manifest": manifest}

    # skip-stale / skip-duplicate: never regress manifest/latest or root landing.
    latest = manifest.get("latest")
    if latest is None:
        # First publication state is missing/corrupt for this context; cannot safely
        # restore a previous latest, but we also must not promote this incoming run.
        raise PublishError(
            f"{action}: {reason}, but no existing latest publication is recorded to preserve"
        )
    latest_pub = Publication.from_dict(latest, "latest")
    # Ensure root index points to recorded latest even if upstream tooling overwrote it.
    write_root_index(site_dir, latest_pub.report_path)
    # Manifest remains unchanged (idempotent for duplicates; stale runs do not become latest).
    validate_manifest_state(site_dir, manifest)
    return {"action": action, "reason": reason, "manifest": manifest}


def cmd_validate_input(args: argparse.Namespace) -> int:
    validate_pages_input(Path(args.results_dir))
    print(f"pages input OK: {args.results_dir}")
    return 0


def cmd_decide(args: argparse.Namespace) -> int:
    incoming = Publication.from_args(args)
    if args.results_dir:
        validate_pages_input(Path(args.results_dir))
    manifest = load_manifest(_manifest_path(Path(args.site_dir)))
    action, reason = decide(manifest, incoming)
    print(json.dumps({"action": action, "reason": reason, "identity": incoming.identity}, sort_keys=True))
    return 0


def cmd_apply(args: argparse.Namespace) -> int:
    incoming = Publication.from_args(args)
    site_dir = Path(args.site_dir)
    result = apply_publication(site_dir, _manifest_path(site_dir), incoming)
    print(json.dumps({"action": result["action"], "reason": result["reason"]}, sort_keys=True))
    return 0


def cmd_validate_state(args: argparse.Namespace) -> int:
    site_dir = Path(args.site_dir)
    manifest = load_manifest(_manifest_path(site_dir))
    validate_manifest_state(site_dir, manifest)
    print(f"publication state OK: {site_dir}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    def add_publication_args(cmd: argparse.ArgumentParser) -> None:
        cmd.add_argument("--run-id", required=True)
        cmd.add_argument("--run-number", required=True)
        cmd.add_argument("--run-attempt", required=True)
        cmd.add_argument("--event", required=True)
        cmd.add_argument("--sha", required=True)
        cmd.add_argument("--population", required=True)
        cmd.add_argument("--report-path", required=True)

    p_validate_input = sub.add_parser("validate-input", help="validate merged Allure Pages input artifact")
    p_validate_input.add_argument("--results-dir", required=True)
    p_validate_input.set_defaults(func=cmd_validate_input)

    p_decide = sub.add_parser("decide", help="decide whether incoming publication may become latest")
    p_decide.add_argument("--site-dir", required=True)
    p_decide.add_argument("--results-dir")
    add_publication_args(p_decide)
    p_decide.set_defaults(func=cmd_decide)

    p_apply = sub.add_parser("apply", help="apply monotonic/idempotent publication state")
    p_apply.add_argument("--site-dir", required=True)
    add_publication_args(p_apply)
    p_apply.set_defaults(func=cmd_apply)

    p_validate_state = sub.add_parser("validate-state", help="validate manifest and latest/index consistency")
    p_validate_state.add_argument("--site-dir", required=True)
    p_validate_state.set_defaults(func=cmd_validate_state)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except PublishError as exc:
        print(f"allure-pages publication error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
