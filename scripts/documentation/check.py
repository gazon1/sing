#!/usr/bin/env python3
"""check.py — documentation knowledge concept status checker (Phase 1a spike).

Commands:
  status <concept>          print computed status
  show <concept>            print concept text + status + snapshots
  affected [paths...]        concepts whose sources intersect given paths
  stamp <concept> [--verified --by NAME]   append generated/verified snapshot
  verify                    validate all concepts (frontmatter, sources, registry)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
KNOWLEDGE = ROOT / "knowledge"
FACTS = KNOWLEDGE / "facts.yaml"

STATE_FIELDS = {"generated", "verified"}


def git_blob(path: pathlib.Path) -> str:
    """Git blob OID of the working-tree file (hash-object, path-aware)."""
    rel = path.relative_to(ROOT).as_posix()
    out = subprocess.run(
        ["git", "-C", str(ROOT), "hash-object", "--path=" + rel, str(path)],
        capture_output=True, text=True, check=True,
    )
    return out.stdout.strip()


def git_blob_head(rel: str) -> str | None:
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD:" + rel],
            capture_output=True, text=True, check=True,
        )
        return out.stdout.strip()
    except subprocess.CalledProcessError:
        return None


def is_dirty(path: pathlib.Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    r = subprocess.run(
        ["git", "-C", str(ROOT), "status", "--porcelain", "--", rel],
        capture_output=True, text=True, check=True,
    )
    return bool(r.stdout.strip())


def parse_concept(path: pathlib.Path):
    from ruamel.yaml import YAML
    y = YAML()
    y.preserve_quotes = True
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---"):
        raise ValueError(f"no frontmatter: {path}")
    parts = text.split("---", 2)
    fm = y.load(parts[1])
    body = parts[2].lstrip("\n")
    return fm, body, text


def body_state(fm, body: str) -> str:
    # canonical: drop state fields, sort keys, json.dumps, + body
    data = {k: v for k, v in fm.items() if k not in STATE_FIELDS}
    # normalize ruamel structures to plain python
    def norm(o):
        if isinstance(o, dict):
            return {str(k): norm(v) for k, v in o.items()}
        if isinstance(o, list):
            return [norm(x) for x in o]
        return o
    nd = norm(data)
    canonical = json.dumps(nd, sort_keys=True, ensure_ascii=False, default=str)
    payload = "FRONTMATTER\n" + canonical + "\nBODY\n" + body
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def resolve_source(res: str) -> pathlib.Path | None:
    if not res.startswith("repo:"):
        return None
    rel = res[len("repo:"):].lstrip("/")
    p = ROOT / rel
    return p if p.exists() else None


def concept_path(name: str) -> pathlib.Path:
    # search knowledge for <name>.md
    for p in KNOWLEDGE.rglob("*.md"):
        if p.stem == name:
            return p
    raise FileNotFoundError(f"concept not found: {name}")


def compute_status(fm, body: str) -> str:
    cur_body = body_state(fm, body)
    cur_sources = {}
    for s in (fm.get("sources") or []):
        p = resolve_source(s["resource"])
        if p is None:
            cur_sources[s["id"]] = None
        else:
            cur_sources[s["id"]] = git_blob(p)

    verified = fm.get("verified") or []
    generated = fm.get("generated") or []

    last_verified = verified[-1] if verified else None
    last_generated = generated[-1] if generated else None

    if not verified and not generated:
        return "unstamped"

    def src_match(snap):
        if snap is None:
            return False
        sv = snap.get("source_state") or {}
        return all(cur_sources.get(k) == v and v is not None for k, v in sv.items()) and set(sv.keys()) == set(cur_sources.keys())

    body_matches_verified = last_verified is not None and last_verified.get("body_state") == cur_body
    src_matches_verified = last_verified is not None and src_match(last_verified)
    body_matches_generated = last_generated is not None and last_generated.get("body_state") == cur_body
    src_matches_generated = last_generated is not None and src_match(last_generated)

    if body_matches_verified and src_matches_verified:
        return "verified"
    if body_matches_generated and src_matches_generated and last_verified is None:
        return "generated"
    if body_matches_generated and src_matches_generated and not (body_matches_verified and src_matches_verified):
        # generated newer than verified but matches
        if last_verified is None:
            return "generated"
    if not body_matches_verified and src_matches_verified:
        return "body_stale"
    if body_matches_verified and not src_matches_verified:
        return "sources_stale"
    if not body_matches_verified and not src_matches_verified:
        # could be generated state
        if body_matches_generated and src_matches_generated:
            return "generated"
        return "stale"
    return "stale"


def cmd_status(args):
    p = concept_path(args.concept)
    fm, body, _ = parse_concept(p)
    print(compute_status(fm, body))


def cmd_show(args):
    p = concept_path(args.concept)
    fm, body, _ = parse_concept(p)
    st = compute_status(fm, body)
    print(f"# {fm.get('title')}")
    print(f"Status: {st}")
    print()
    print(body)
    print("---generated---")
    print(fm.get("generated"))
    print("---verified---")
    print(fm.get("verified"))


def cmd_affected(args):
    paths = args.paths or []
    # changed files from git if --git-diff
    changed = set()
    if args.git_diff:
        out = subprocess.run(
            ["git", "-C", str(ROOT), "diff", "--name-only", args.git_diff],
            capture_output=True, text=True, check=True,
        )
        changed = set(out.stdout.split())
    else:
        changed = set()
        for pp in paths:
            try:
                changed.add(pathlib.Path(pp).resolve().relative_to(ROOT).as_posix())
            except ValueError:
                changed.add(pp)
    for p in KNOWLEDGE.rglob("*.md"):
        try:
            fm, body, _ = parse_concept(p)
        except Exception:
            continue
        srcs = set()
        for s in (fm.get("sources") or []):
            sp = resolve_source(s["resource"])
            if sp is not None:
                srcs.add(sp.relative_to(ROOT).as_posix())
        if srcs & changed:
            print(p.relative_to(ROOT).as_posix())


def cmd_stamp(args):
    p = concept_path(args.concept)
    fm, body, text = parse_concept(p)
    # check clean sources
    for s in (fm.get("sources") or []):
        sp = resolve_source(s["resource"])
        if sp is None:
            print(f"missing source: {s['resource']}", file=sys.stderr)
            sys.exit(1)
        if is_dirty(sp):
            print(f"dirty source: {sp}", file=sys.stderr)
            sys.exit(1)
    # compute state
    cur_sources = {s["id"]: git_blob(resolve_source(s["resource"])) for s in (fm.get("sources") or [])}
    bs = body_state(fm, body)
    from ruamel.yaml import YAML
    y = YAML()
    y.preserve_quotes = True
    y.default_flow_style = False

    if args.verified:
        if not args.by or not args.by.startswith("human:"):
            print("--verified requires --by human:...", file=sys.stderr)
            sys.exit(1)
        # preview
        print("Sources:")
        for sid, oid in cur_sources.items():
            print(f"  {sid}: {oid}")
        print(f"body_state: {bs}")
        if not args.yes:
            try:
                ans = input("Confirm human verification? [y/N] ").strip().lower()
            except EOFError:
                ans = ""
            if ans not in ("y", "yes"):
                print("aborted", file=sys.stderr)
                sys.exit(1)
        snap = {"by": args.by, "source_state": cur_sources, "body_state": bs}
        fm.setdefault("verified", []).append(snap)
    else:
        snap = {"by": "agent", "source_state": cur_sources, "body_state": bs}
        fm.setdefault("generated", []).append(snap)

    # write back preserving frontmatter delimiter
    with p.open("w", encoding="utf-8") as f:
        f.write("---\n")
        y.dump(fm, f)
        f.write("---\n")
        f.write(body if body.startswith("\n") else "\n" + body)
    print("stamped", args.concept)


def cmd_verify(args):
    ok = True
    parsed = []
    for p in KNOWLEDGE.rglob("*.md"):
        try:
            fm, body, _ = parse_concept(p)
            parsed.append((p, fm, body))
        except Exception as e:
            print(f"INVALID {p}: {e}")
            ok = False
            continue
        for s in (fm.get("sources") or []):
            if resolve_source(s["resource"]) is None:
                print(f"MISSING SOURCE {p}: {s['resource']}")
                ok = False
    # facts registry
    if FACTS.exists():
        from ruamel.yaml import YAML
        y = YAML()
        facts = y.load(FACTS.read_text())
        ids = {f["id"] for f in (facts.get("facts") or [])}
        for p, fm, body in parsed:
            for fid in (fm.get("canonical_for") or []):
                if fid not in ids:
                    print(f"UNKNOWN FACT {p}: {fid}")
                    ok = False
    if not getattr(args, "quiet", False):
        print("OK" if ok else "FAIL")
    sys.exit(0 if ok else 1)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("status"); s.add_argument("concept"); s.set_defaults(func=cmd_status)
    sh = sub.add_parser("show"); sh.add_argument("concept"); sh.set_defaults(func=cmd_show)
    af = sub.add_parser("affected"); af.add_argument("paths", nargs="*"); af.add_argument("--git-diff"); af.set_defaults(func=cmd_affected)
    st = sub.add_parser("stamp"); st.add_argument("concept"); st.add_argument("--verified", action="store_true"); st.add_argument("--by"); st.add_argument("--yes", action="store_true"); st.set_defaults(func=cmd_stamp)
    v = sub.add_parser("verify"); v.add_argument("--quiet", action="store_true"); v.set_defaults(func=cmd_verify)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
