#!/usr/bin/env python3
"""Allure Pages publication: history bootstrap, manifest, monotonic latest, and validation.

Covers #2756/#2764/#2766/#2767/#2769:
- merged Pages input validation
- gh-pages history bootstrap from tarball content/outcome (testable without network)
- per-population (fast/full) namespaces with isolated manifest/history/latest state
- ordering by (run_number, run_attempt), idempotency by (run_id, run_attempt)
- monotonic latest/landing updates, manifest/index validation
- bounded per-population report retention without removing latest/required history
- publish-tree invariants before deploy

Dependency-light and usable from CI workflow steps and local tests.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

SCHEMA_VERSION = 2
SUPPORTED_POPULATIONS = ("fast", "full")
REPORTS_PER_POPULATION = 20
NUMERIC_REPORT_RE = re.compile(r"^\d+$")


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

    def __post_init__(self) -> None:
        object.__setattr__(self, "population", _population(self.population))

    @classmethod
    def from_args(cls, args: argparse.Namespace) -> "Publication":
        return cls(
            run_id=_positive_int(args.run_id, "run-id"),
            run_number=_positive_int(args.run_number, "run-number"),
            run_attempt=_positive_int(args.run_attempt, "run-attempt"),
            event=_non_empty(args.event, "event"),
            sha=_non_empty(args.sha, "sha"),
            population=_population(args.population),
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
                population=_population(data.get("population")),
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


def _population(value: Any) -> str:
    population = _non_empty(value, "population")
    if population not in SUPPORTED_POPULATIONS:
        raise PublishError(
            f"invalid population: expected one of {list(SUPPORTED_POPULATIONS)}, got {population!r}"
        )
    return population


def manifest_path(population_dir: Path) -> Path:
    return population_dir / "allure-manifest.json"


def load_manifest(path: Path, population: str | None = None) -> dict[str, Any]:
    if not path.exists():
        pop = _population(population) if population is not None else None
        return {"schema_version": SCHEMA_VERSION, "population": pop, "latest": None, "publications": {}}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise PublishError(f"malformed publication manifest: {exc}") from exc
    if not isinstance(data, dict):
        raise PublishError("malformed publication manifest: expected object")
    version = data.get("schema_version")
    if version not in (1, SCHEMA_VERSION):
        raise PublishError(f"unsupported publication manifest schema_version: {version!r}")
    publications = data.get("publications")
    latest = data.get("latest", None)
    if not isinstance(publications, dict):
        raise PublishError("malformed publication manifest: publications must be an object")
    if latest is not None and not isinstance(latest, dict):
        raise PublishError("malformed publication manifest: latest must be an object or null")

    manifest_population = data.get("population", population)
    if manifest_population is not None:
        manifest_population = _population(manifest_population)

    normalized: dict[str, Any] = {}
    for key, value in publications.items():
        pub = Publication.from_dict(value, f"publications[{key!r}]")
        if key != pub.identity:
            raise PublishError(
                f"malformed publication manifest: identity key {key!r} does not match {pub.identity!r}"
            )
        if manifest_population and pub.population != manifest_population:
            raise PublishError(
                f"malformed publication manifest: publication {key!r} population {pub.population!r} "
                f"does not match manifest population {manifest_population!r}"
            )
        normalized[key] = pub.to_dict()

    latest_norm = None
    if latest is not None:
        latest_pub = Publication.from_dict(latest, "latest")
        if latest_pub.identity not in normalized:
            raise PublishError("malformed publication manifest: latest is not present in publications")
        if manifest_population and latest_pub.population != manifest_population:
            raise PublishError("malformed publication manifest: latest population mismatch")
        latest_norm = normalized[latest_pub.identity]
    return {
        "schema_version": SCHEMA_VERSION,
        "population": manifest_population,
        "latest": latest_norm,
        "publications": normalized,
    }


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
    manifest_population = manifest.get("population")
    if manifest_population and incoming.population != manifest_population:
        raise PublishError(
            f"incoming population {incoming.population!r} does not match manifest population {manifest_population!r}"
        )
    publications = manifest["publications"]
    existing = publications.get(incoming.identity)
    latest = manifest.get("latest")
    latest_key = Publication.from_dict(latest, "latest").order_key if latest is not None else None

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


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def write_population_index(population_dir: Path, report_path: str) -> None:
    target = f"{report_path}/index.html"
    html = (
        '<!DOCTYPE html><meta charset="utf-8">'
        f'<meta http-equiv="refresh" content="0; URL={target}">'
        '<meta http-equiv="Pragma" content="no-cache">'
        '<meta http-equiv="Expires" content="0">\n'
    )
    _atomic_write_text(population_dir / "index.html", html)


def build_root_index(populations: Iterable[str]) -> str:
    items = [f'<li><a href="{pop}/index.html">{pop} latest report</a></li>' for pop in populations]
    return (
        '<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        "<title>Allure reports</title></head><body>"
        "<h1>Allure reports</h1><p>Population-specific latest reports:</p>"
        f"<ul>{''.join(items)}</ul></body></html>\n"
    )


def present_populations(site_dir: Path) -> list[str]:
    present = [p for p in SUPPORTED_POPULATIONS if manifest_path(site_dir / p).is_file()]
    return present


def write_root_index(site_dir: Path, populations: Iterable[str] | None = None) -> None:
    pops = list(populations) if populations is not None else present_populations(site_dir)
    if not pops:
        pops = list(SUPPORTED_POPULATIONS)
    _atomic_write_text(site_dir / "index.html", build_root_index(pops))


def ensure_population_namespace(gh_pages_dir: Path, population: str) -> Path:
    """Ensure gh-pages/<population> exists with isolated history state.

    Mixed root last-history is never seeded into population namespaces (ADR #2761/#2766).
    """
    _population(population)
    pop_dir = gh_pages_dir / population
    pop_dir.mkdir(parents=True, exist_ok=True)
    (pop_dir / "last-history").mkdir(parents=True, exist_ok=True)
    return pop_dir


def prepare_namespace(gh_pages_dir: Path, population: str) -> dict[str, Any]:
    gh_pages_dir.mkdir(parents=True, exist_ok=True)
    pop_dir = ensure_population_namespace(gh_pages_dir, population)
    mpath = manifest_path(pop_dir)
    manifest = load_manifest(mpath, population)
    if manifest.get("population") is None:
        manifest["population"] = population
        save_manifest(mpath, manifest)
    fresh = manifest.get("latest") is None and not manifest.get("publications")
    return {"population": population, "path": str(pop_dir), "clean_history": fresh}


def validate_pages_input(results_dir: Path) -> None:
    if not results_dir.exists():
        raise PublishError(f"missing Pages input artifact directory: {results_dir}")
    if not results_dir.is_dir():
        raise PublishError(f"Pages input artifact is not a directory: {results_dir}")
    if not any(results_dir.rglob("*-result.json")):
        raise PublishError(
            f"malformed Pages input artifact: no *-result.json files in {results_dir}"
        )


def validate_population_state(population_dir: Path, population: str, manifest: dict[str, Any] | None = None) -> Publication:
    if manifest is None:
        manifest = load_manifest(manifest_path(population_dir), population)
    latest = manifest.get("latest")
    if latest is None:
        raise PublishError(f"publication manifest for {population} has no latest entry")
    latest_pub = Publication.from_dict(latest, "latest")
    if latest_pub.population != population:
        raise PublishError(f"manifest latest population mismatch for {population}")
    report_dir = population_dir / latest_pub.report_path
    if not report_dir.is_dir():
        raise PublishError(f"manifest latest points to missing report: {population}/{latest_pub.report_path}")
    if not (report_dir / "index.html").is_file():
        raise PublishError(
            f"manifest latest report is missing index.html: {population}/{latest_pub.report_path}"
        )
    pop_index = population_dir / "index.html"
    if not pop_index.is_file():
        raise PublishError(f"population index.html is missing: {population}")
    content = pop_index.read_text(encoding="utf-8")
    expected = f"URL={latest_pub.report_path}/index.html"
    if expected not in content:
        raise PublishError(
            f"population index.html does not point to latest report: {population} expected {expected!r}"
        )
    if not (population_dir / "last-history").is_dir():
        raise PublishError(f"required history directory missing: {population}/last-history")
    return latest_pub


def validate_publish_tree(site_dir: Path, populations: Iterable[str] | None = None) -> None:
    if not site_dir.exists() or not site_dir.is_dir():
        raise PublishError(f"publish tree does not exist: {site_dir}")
    for git_dir in site_dir.rglob(".git"):
        raise PublishError(f"publish tree contains git metadata: {git_dir.relative_to(site_dir)}")
    for archive in list(site_dir.rglob("*.tar.gz")) + list(site_dir.rglob("*.tgz")) + list(site_dir.rglob("*.zip")):
        # keep zip out? temp archives - tar/tgz explicitly required; be conservative for tarballs
        pass
    for archive in list(site_dir.rglob("*.tar.gz")) + list(site_dir.rglob("*.tgz")):
        raise PublishError(f"publish tree contains temporary archive: {archive.relative_to(site_dir)}")
    root_index = site_dir / "index.html"
    if not root_index.is_file():
        raise PublishError("publish tree missing root index.html")

    pops = list(populations) if populations is not None else present_populations(site_dir)
    if not pops:
        raise PublishError("publish tree has no published populations")
    root_content = root_index.read_text(encoding="utf-8")
    for pop in pops:
        pop = _population(pop)
        pop_dir = site_dir / pop
        if not pop_dir.is_dir():
            raise PublishError(f"publish tree missing population directory: {pop}")
        if f"{pop}/index.html" not in root_content:
            raise PublishError(f"root index.html does not link to population {pop}")
        mpath = manifest_path(pop_dir)
        if not mpath.is_file():
            raise PublishError(f"publish tree missing manifest for population {pop}")
        manifest = load_manifest(mpath, pop)
        validate_population_state(pop_dir, pop, manifest)

    cname = site_dir / "CNAME"
    if cname.exists() and (not cname.is_file() or not cname.read_text(encoding="utf-8").strip()):
        raise PublishError("publish tree contains invalid CNAME")


def prune_population(population_dir: Path, population: str, keep: int = REPORTS_PER_POPULATION) -> dict[str, Any]:
    keep = _positive_int(keep, "keep-reports")
    mpath = manifest_path(population_dir)
    manifest = load_manifest(mpath, population)
    publications = manifest.get("publications", {})
    latest = manifest.get("latest")
    if latest is None:
        return {"removed": [], "kept": 0}

    latest_path = latest["report_path"]
    latest_identity = (latest["run_id"], latest["run_attempt"])

    numeric_dirs = [
        child
        for child in population_dir.iterdir()
        if child.is_dir() and NUMERIC_REPORT_RE.match(child.name)
    ]
    ordered = sorted(numeric_dirs, key=lambda p: (int(p.name), p.name))
    keep_paths = {p.name for p in ordered[-keep:]} if keep < len(ordered) else {p.name for p in ordered}
    keep_paths.add(latest_path)

    removed: list[str] = []
    for p in ordered:
        if p.name in keep_paths or p.name == latest_path or p.name == "last-history":
            continue
        shutil.rmtree(p)
        removed.append(p.name)

    if removed:
        removed_set = set(removed)
        new_publications = {}
        for ident, pub_dict in publications.items():
            if pub_dict["report_path"] in removed_set and (pub_dict["run_id"], pub_dict["run_attempt"]) != latest_identity:
                continue
            new_publications[ident] = pub_dict
        manifest["publications"] = new_publications
        save_manifest(mpath, manifest)
    return {"removed": removed, "kept": max(len(ordered) - len(removed), 0)}


def reconcile_population(site_dir: Path, population: str) -> None:
    """Ensure population/root indexes reflect recorded latest without promoting new runs."""
    population_dir = site_dir / population
    manifest = load_manifest(manifest_path(population_dir), population)
    latest = manifest.get("latest")
    if latest is None:
        return
    latest_pub = Publication.from_dict(latest, "latest")
    write_population_index(population_dir, latest_pub.report_path)
    pops = present_populations(site_dir)
    if pops:
        write_root_index(site_dir, pops)


def apply_publication(site_dir: Path, incoming: Publication, keep_reports: int = REPORTS_PER_POPULATION) -> dict[str, Any]:
    population_dir = site_dir / incoming.population
    mpath = manifest_path(population_dir)
    if not population_dir.is_dir():
        raise PublishError(f"population namespace does not exist: {incoming.population}")
    manifest = load_manifest(mpath, incoming.population)
    if manifest.get("population") is None:
        manifest["population"] = incoming.population
    action, reason = decide(manifest, incoming)
    publications = dict(manifest["publications"])

    if action == "publish":
        report_dir = population_dir / incoming.report_path
        if not report_dir.is_dir():
            raise PublishError(f"latest report target does not exist: {incoming.population}/{incoming.report_path}")
        if not (report_dir / "index.html").is_file():
            raise PublishError(
                f"latest report target is missing index.html: {incoming.population}/{incoming.report_path}"
            )
        publications[incoming.identity] = incoming.to_dict()
        manifest = {
            "schema_version": SCHEMA_VERSION,
            "population": incoming.population,
            "latest": incoming.to_dict(),
            "publications": publications,
        }
        write_population_index(population_dir, incoming.report_path)
        save_manifest(mpath, manifest)
        prune_result = prune_population(population_dir, incoming.population, keep_reports)
        manifest = load_manifest(mpath, incoming.population)
        validate_population_state(population_dir, incoming.population, manifest)
        pops = present_populations(site_dir)
        if incoming.population not in pops:
            pops.append(incoming.population)
            pops = [p for p in SUPPORTED_POPULATIONS if p in pops]
        if pops:
            write_root_index(site_dir, pops)
        return {"action": action, "reason": reason, "pruned": prune_result["removed"]}

    latest = manifest.get("latest")
    if latest is None:
        raise PublishError(
            f"{action}: {reason}, but no existing latest publication is recorded to preserve"
        )
    reconcile_population(site_dir, incoming.population)
    validate_population_state(population_dir, incoming.population, manifest)
    return {"action": action, "reason": reason, "pruned": []}


def _cleanup_dir(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def bootstrap_history_from_tarball(
    gh_pages_dir: Path,
    tarball_path: Path | None,
    http_status: str | int | None,
) -> dict[str, Any]:
    """Extract gh-pages tarball into gh_pages_dir or initialize clean state.

    - 200 + valid tarball: extract (strip top-level dir), preserving branch contents.
    - 404: explicit first-publication case, clean empty state.
    - other failures/malformed/missing tarball: cleanup partial extraction and leave
      clean empty state, returning failure status (caller must not publish over site).
    """
    status = str(http_status) if http_status is not None else "missing"

    if status == "404":
        _cleanup_dir(gh_pages_dir)
        (gh_pages_dir / "last-history").mkdir(parents=True, exist_ok=True)
        return {"status": "missing-branch", "http_status": 404, "extracted": False}

    if status != "200":
        _cleanup_dir(gh_pages_dir)
        (gh_pages_dir / "last-history").mkdir(parents=True, exist_ok=True)
        return {"status": "download-failed", "http_status": status, "extracted": False}

    if tarball_path is None or not Path(tarball_path).exists():
        _cleanup_dir(gh_pages_dir)
        (gh_pages_dir / "last-history").mkdir(parents=True, exist_ok=True)
        return {"status": "missing-tarball", "http_status": 200, "extracted": False}

    _cleanup_dir(gh_pages_dir)
    try:
        with tarfile.open(tarball_path, "r:gz") as tar:
            members = tar.getmembers()
            if not members:
                raise PublishError("empty tarball")
            top_levels = {m.name.split("/", 1)[0] for m in members if m.name}
            if len(top_levels) != 1:
                raise PublishError("unexpected tarball layout")
            prefix = next(iter(top_levels)) + "/"
            resolved_root = gh_pages_dir.resolve()
            for member in members:
                if not member.name.startswith(prefix):
                    continue
                rel = member.name[len(prefix):]
                if not rel:
                    continue
                target = (gh_pages_dir / rel).resolve()
                if target != resolved_root and resolved_root not in target.parents:
                    raise PublishError("tarball contains unsafe path")
            for member in members:
                if not member.name.startswith(prefix):
                    continue
                rel = member.name[len(prefix):]
                if not rel:
                    continue
                member.name = rel
                tar.extract(member, gh_pages_dir)
    except Exception as exc:
        _cleanup_dir(gh_pages_dir)
        (gh_pages_dir / "last-history").mkdir(parents=True, exist_ok=True)
        return {"status": "malformed-tarball", "http_status": 200, "extracted": False, "error": str(exc)}

    (gh_pages_dir / "last-history").mkdir(parents=True, exist_ok=True)
    return {"status": "ok", "http_status": 200, "extracted": True}


def tree_stats(path: Path) -> dict[str, int]:
    files = 0
    size = 0
    if not path.exists():
        return {"files": 0, "bytes": 0}
    for p in path.rglob("*"):
        try:
            if p.is_file() and not p.is_symlink():
                files += 1
                size += p.stat().st_size
        except OSError:
            pass
    return {"files": files, "bytes": size}


def cmd_validate_input(args: argparse.Namespace) -> int:
    validate_pages_input(Path(args.results_dir))
    print(f"pages input OK: {args.results_dir}")
    return 0


def cmd_bootstrap_history(args: argparse.Namespace) -> int:
    tarball = Path(args.tarball) if args.tarball else None
    result = bootstrap_history_from_tarball(Path(args.gh_pages_dir), tarball, args.http_status)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] in {"ok", "missing-branch"} else 1


def cmd_prepare_namespace(args: argparse.Namespace) -> int:
    result = prepare_namespace(Path(args.gh_pages_dir), _population(args.population))
    print(json.dumps(result, sort_keys=True))
    return 0


def cmd_decide(args: argparse.Namespace) -> int:
    incoming = Publication.from_args(args)
    if args.results_dir:
        validate_pages_input(Path(args.results_dir))
    population_dir = Path(args.site_dir) / incoming.population
    mpath = manifest_path(population_dir)
    manifest = load_manifest(mpath, incoming.population)
    if manifest.get("population") is None:
        manifest["population"] = incoming.population
    action, reason = decide(manifest, incoming)
    print(json.dumps({"action": action, "reason": reason, "identity": incoming.identity, "population": incoming.population}, sort_keys=True))
    return 0


def cmd_apply(args: argparse.Namespace) -> int:
    incoming = Publication.from_args(args)
    site_dir = Path(args.site_dir)
    keep = _positive_int(args.keep_reports, "keep-reports")
    result = apply_publication(site_dir, incoming, keep)
    print(json.dumps({"action": result["action"], "reason": result["reason"], "pruned": result.get("pruned", [])}, sort_keys=True))
    return 0


def cmd_reconcile(args: argparse.Namespace) -> int:
    population = _population(args.population)
    reconcile_population(Path(args.site_dir), population)
    print(f"reconciled: {population}")
    return 0


def cmd_validate_state(args: argparse.Namespace) -> int:
    site_dir = Path(args.site_dir)
    populations = [_population(p) for p in args.population] if args.population else present_populations(site_dir)
    if not populations:
        raise PublishError("no published populations found to validate")
    validate_publish_tree(site_dir, populations)
    print(f"publication state OK: {site_dir}")
    return 0


def cmd_prune(args: argparse.Namespace) -> int:
    population = _population(args.population)
    population_dir = Path(args.site_dir) / population
    if not population_dir.is_dir():
        raise PublishError(f"population namespace does not exist: {population}")
    result = prune_population(population_dir, population, _positive_int(args.keep_reports, "keep-reports"))
    print(json.dumps(result, sort_keys=True))
    return 0


def cmd_measure(args: argparse.Namespace) -> int:
    stats = tree_stats(Path(args.path))
    print(json.dumps(stats, sort_keys=True))
    return 0


def cmd_write_root_index(args: argparse.Namespace) -> int:
    site_dir = Path(args.site_dir)
    populations = [_population(p) for p in args.population] if args.population else present_populations(site_dir)
    write_root_index(site_dir, populations or list(SUPPORTED_POPULATIONS))
    print(f"root index written: {site_dir / 'index.html'}")
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

    p_vi = sub.add_parser("validate-input", help="validate merged Allure Pages input artifact")
    p_vi.add_argument("--results-dir", required=True)
    p_vi.set_defaults(func=cmd_validate_input)

    p_bh = sub.add_parser("bootstrap-history", help="extract gh-pages tarball or initialize clean history state")
    p_bh.add_argument("--gh-pages-dir", required=True)
    p_bh.add_argument("--tarball")
    p_bh.add_argument("--http-status")
    p_bh.set_defaults(func=cmd_bootstrap_history)

    p_pn = sub.add_parser("prepare-namespace", help="prepare per-population namespace with isolated history")
    p_pn.add_argument("--gh-pages-dir", required=True)
    p_pn.add_argument("--population", required=True)
    p_pn.set_defaults(func=cmd_prepare_namespace)

    p_decide = sub.add_parser("decide", help="decide whether incoming publication may become latest for its population")
    p_decide.add_argument("--site-dir", required=True)
    p_decide.add_argument("--results-dir")
    add_publication_args(p_decide)
    p_decide.set_defaults(func=cmd_decide)

    p_apply = sub.add_parser("apply", help="apply monotonic/idempotent publication state for a population")
    p_apply.add_argument("--site-dir", required=True)
    p_apply.add_argument("--keep-reports", default=str(REPORTS_PER_POPULATION))
    add_publication_args(p_apply)
    p_apply.set_defaults(func=cmd_apply)

    p_reconcile = sub.add_parser("reconcile", help="reconcile indexes to recorded latest for a population")
    p_reconcile.add_argument("--site-dir", required=True)
    p_reconcile.add_argument("--population", required=True)
    p_reconcile.set_defaults(func=cmd_reconcile)

    p_validate = sub.add_parser("validate-state", help="validate publish tree, manifests and latest/index consistency")
    p_validate.add_argument("--site-dir", required=True)
    p_validate.add_argument("--population", action="append")
    p_validate.set_defaults(func=cmd_validate_state)

    p_prune = sub.add_parser("prune", help="prune old per-population reports while keeping latest/required history")
    p_prune.add_argument("--site-dir", required=True)
    p_prune.add_argument("--population", required=True)
    p_prune.add_argument("--keep-reports", default=str(REPORTS_PER_POPULATION))
    p_prune.set_defaults(func=cmd_prune)

    p_measure = sub.add_parser("measure", help="report file count and byte size of a directory tree")
    p_measure.add_argument("--path", required=True)
    p_measure.set_defaults(func=cmd_measure)

    p_root = sub.add_parser("write-root-index", help="write root navigation index linking populations")
    p_root.add_argument("--site-dir", required=True)
    p_root.add_argument("--population", action="append")
    p_root.set_defaults(func=cmd_write_root_index)

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
