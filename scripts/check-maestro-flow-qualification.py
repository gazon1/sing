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

   The *frozen set* — the flows that were in a blocking scope when the rule
   was introduced — is not part of that baseline. It lives in its own
   artifact, ``config/docs/maestro-flow-qualification-initial.yaml``, and it
   is pinned by a digest kept here, in the gate that enforces it. The two are
   independent on purpose: a baseline that carried its own frozen set could be
   edited once to admit a flow nobody ever ran, and a gate whose trusted set
   lives in the file it also updates cannot tell the difference. So the
   baseline map can only carry fingerprints of flows in the frozen set, and
   the frozen set can only grow through a change to this file — a change that
   reads as what it is: a decision to admit flows without qualifying them.

   The baseline map is pinned the same way: its digest is kept here, so an
   edited fingerprint -- a changed flow nobody qualified -- is a source
   change and not a data change.

   Retiring a flow splits the two artifacts in opposite directions. The frozen
   set keeps the name: it is the record of the flows that were in a blocking
   scope when the rule was introduced, and a name without a file behind it
   qualifies nothing. The baseline map drops the fingerprint: the map only
   shrinks, and a flow that comes back to the catalogue is qualified instead
   of admitted by a fingerprint nobody re-ran. Every entry in the map points
   at a flow that is still in the catalogue; the gate reports an entry that
   points at a flow the catalogue no longer names, so a retirement that
   forgets to shrink the map is caught here. A catalogue flow that was
   introduced after the rule is not in the map and has to be qualified or
   excepted like any other new flow. ADR
   ``docs/decisions/2026-11-25-a-retired-flow-leaves-the-baseline-map.md``.
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
baseline, the frozen set or the qualification file.

The currency of a record is the fingerprint. The build the attempts ran is
the *program* the record is about -- the gate refuses a record whose two
build values name two different programs -- but a change to the application
code does not invalidate a record: the admitted flow keeps running in the
blocking suite, where an application regression is caught. The gate is an
admission rule, not a regression detector, and it does not compare the
recorded build with the checkout it runs in. ADR
``docs/decisions/2026-11-25-qualification-currency-is-the-flow-not-the-build.md``
records the decision and its cost.

Usage:
    python3 scripts/check-maestro-flow-qualification.py [--json] [--check]
        [--root DIR] [--catalogue FILE] [--baseline FILE] [--evidence FILE]
        [--initial FILE] [--init-baseline] [--init-evidence]
        [--print-initial-digest] [--print-baseline-digest]

Exit codes:
    0 — every flow in a blocking automated scope is admitted
    1 — an unqualified, flaky, stale or unexcepted flow is in a blocking scope
    2 — the catalogue, baseline, frozen set or evidence file could not be read
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

from emulator.maestro._engine import parse_engine_version  # noqa: E402
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
DEFAULT_INITIAL = ROOT / "config" / "docs" / "maestro-flow-qualification-initial.yaml"
DEFAULT_EVIDENCE = ROOT / "config" / "docs" / "maestro-flow-qualification.yaml"

#: The pinned digest of the frozen set (the flows that were in a blocking scope
#: when the rule was introduced).
#:
#: The frozen set lives in its own artifact, and this digest is the lock on
#: it. The two are kept apart on purpose: a frozen set stored next to the
#: baseline map it guards is a frozen set that one edit can grow, and growing
#: it is how an unqualified flow enters a blocking scope. Changing the digest
#: here is how the frozen set is deliberately changed — it is a source change,
#: it is reviewed as one, and it says in the diff that flows were admitted
#: without being qualified. Print the current digest with
#: ``--print-initial-digest``.
FROZEN_SET_DIGEST = "sha256:863ed25a7db9a260fba93474102645baa27359399e94f8665e95a427c576d13d"

#: The pinned digest of the baseline map (flow -> fingerprint).
#:
#: The baseline says which flow content the rule was introduced for. Editing
#: it — changing a fingerprint, adding a flow — is how a changed flow would
#: be admitted without anyone qualifying it. So the map is pinned the same way
#: the frozen set is pinned: the digest lives here, in the gate that enforces
#: it, and a baseline that does not match is refused before a single flow is
#: looked at. Changing the digest here is how the baseline is deliberately
#: changed — a source change, reviewed as one, that says in the diff that
#: flows are admitted without being qualified. Print the current digest with
#: ``--print-baseline-digest``.
BASELINE_DIGEST = "sha256:f5175fed03662a5fecbc79a686fd34ed6183015da1b7c445b59b6065c054b9dd"

#: A reference inside a flow/helper: `file: path`, `- runFlow: path` or
#: `- runScript: path`. The extension is not restricted to YAML: a flow can run
#: a helper script, and content the fingerprint does not cover is content the
#: qualification cannot attest to.
_REF_PATTERN = r"[^\s#'\"]+\.(?:ya?ml|js|mjs|cjs)"
#: The extensions a reference can name. A reference to anything else (an image
#: a capture step reads, for instance) is not a flow the qualification runs.
_REF_EXTENSIONS = (".yaml", ".yml", ".js", ".mjs", ".cjs")
#: The fallback scan, for a document YAML cannot parse: the same references,
#: found by line. It tolerates a trailing comment, because a comment is valid
#: YAML and a reference behind one is a reference.
_LINE_REF = re.compile(rf"(?m)^\s*(?:-\s*)?(?:file|runFlow|runScript):\s*['\"]?({_REF_PATTERN})['\"]?\s*(?:#.*)?$")

#: The number of consecutive attempts a qualification takes.
#:
#: The evidence file names the number of attempts it wants, but it cannot go
#: below this one: the rule this gate holds is that a flow enters a blocking
#: scope on the strength of a *repeated* run, and a file that says one attempt
#: would turn "passes repeatedly" into "passes once". The floor lives in the
#: gate, so lowering it is a source change, reviewed as one.
MIN_REQUIRED_ATTEMPTS = 3

#: The tree a flow is qualified against: the `Maestro/` flow tree, relative to
#: the repository root.
#:
#: The fingerprint, the snapshot and the reference validator have to cover the
#: same content, or the gate promises more than it can attest to. The
#: qualification runs against a snapshot of this tree, so a reference that
#: resolves outside it points at content the fingerprint never saw and the
#: attempts never ran.
QUALIFICATION_TREE = "Maestro"


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


def _tree_root(root: pathlib.Path) -> pathlib.Path:
    """The qualification tree inside a root (the repository or a snapshot)."""
    return (root / QUALIFICATION_TREE).resolve()


def _relative_to_tree(candidate: pathlib.Path, root: pathlib.Path) -> str | None:
    """The path of `candidate` inside the qualification tree, or None."""
    tree = _tree_root(root)
    try:
        return candidate.resolve().relative_to(tree).as_posix()
    except ValueError:
        return None


def resolve_ref(ref: str, source: pathlib.Path, root: pathlib.Path) -> pathlib.Path | None:
    """Resolve a file reference relative to the file that contains it.

    Only a file inside the qualification tree resolves. A reference to
    anything else is not a dependency of the flow -- it is content the
    fingerprint does not cover and the snapshot does not contain -- and
    `dependency_issues` reports it instead of silently dropping it.
    """
    candidate = (source.parent / ref).resolve()
    if _relative_to_tree(candidate, root) is None:
        return None
    return candidate if candidate.is_file() else None


def _collect_references(node: object, values: list[tuple[str, str]]) -> None:
    """Collect the references a parsed document makes, as (kind, value) pairs.

    `file` names a file, `runScript` names a script, `runFlow` names a flow --
    either by path or by the name it is known under.
    """
    if isinstance(node, dict):
        for key, value in node.items():
            if isinstance(value, str):
                if key == "file":
                    values.append(("file", value))
                elif key == "runScript":
                    values.append(("script", value))
                elif key == "runFlow":
                    values.append(("flow", value))
            _collect_references(value, values)
    elif isinstance(node, list):
        for item in node:
            _collect_references(item, values)


def _reference_values(path: pathlib.Path, text: str) -> list[tuple[str, str]]:
    """The references a document makes, read from the document itself.

    A YAML flow is read as YAML, not as lines of text: a line-based scan does
    not know where a value ends in YAML. A trailing comment
    (`file: helper.yaml # shared setup`) is a valid reference that a scan of
    lines does not see, and a reference the scanner misses is a dependency the
    fingerprint does not cover -- the helper changes, the fingerprint does not,
    and a changed helper enters a blocking scope unrequalified.

    A document YAML cannot parse falls back to a line scan: the gate still has
    to say what it found, and a flow Maestro cannot parse cannot run either.
    """
    if path.suffix.lower() in (".yaml", ".yml"):
        try:
            documents = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            documents = None
        if documents is not None:
            values: list[tuple[str, str]] = []
            for document in documents:
                _collect_references(document, values)
            return values
    return [("file", match.group(1)) for match in _LINE_REF.finditer(text)]


def _ref_path(kind: str, value: str) -> str | None:
    """The path a reference names, or None when it names nothing the gate tracks."""
    candidate = value.strip()
    if not candidate:
        return None
    if candidate.lower().endswith(_REF_EXTENSIONS):
        return candidate
    if kind == "flow":
        # A flow can be named instead of pathed (`runFlow: SubFlow`); Maestro
        # looks for `SubFlow.yaml` next to the flow that names it.
        return f"{candidate}.yaml"
    return None


def flow_refs(path: pathlib.Path) -> list[str]:
    """Every file reference the file at `path` makes."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    refs: list[str] = []
    for kind, value in _reference_values(path, text):
        ref = _ref_path(kind, value)
        # The same reference can be named twice -- a `runScript` with an inline
        # path also carries a `file:` line -- and one reference is one
        # dependency.
        if ref is not None and ref not in refs:
            refs.append(ref)
    return refs


def dependency_issues(flow_rel: str, root: pathlib.Path) -> list[str]:
    """References of a flow that the tree it is qualified against cannot cover.

    A reference that leaves the qualification tree -- an absolute path, a
    relative path that climbs out of the repository, or a path that stays in
    the repository but outside `Maestro/` -- points at content that is in
    neither the fingerprint nor the snapshot the attempts run. Such a flow
    cannot be qualified: the evidence would attest to content the gate never
    sees, and an edit there between two attempts would go unnoticed.
    """
    issues: list[str] = []
    seen: set[str] = set()
    stack = [flow_rel]
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        path = root / current
        for ref in flow_refs(path):
            if ref.startswith("/"):
                issues.append(f"{current} references {ref} by absolute path")
                continue
            candidate = (path.parent / ref).resolve()
            try:
                rel = candidate.relative_to(root.resolve()).as_posix()
            except ValueError:
                issues.append(f"{current} references {ref}, which leaves the repository")
                continue
            if _relative_to_tree(candidate, root) is None:
                issues.append(
                    f"{current} references {ref}, which leaves the qualification tree "
                    f"({QUALIFICATION_TREE}/)"
                )
                continue
            if not candidate.is_file():
                # A reference to something that is not a file inside the tree
                # -- a missing helper, a directory -- is content no
                # fingerprint can cover and no snapshot contains. Silently
                # dropping it would let a flow that cannot run pass the
                # validator and spend its attempts on a failure.
                issues.append(
                    f"{current} references {ref}, which is not a file inside the "
                    f"qualification tree ({QUALIFICATION_TREE}/)"
                )
                continue
            if rel not in seen:
                stack.append(rel)
    return issues


def flow_dependencies(flow_rel: str, root: pathlib.Path) -> list[str]:
    """The flow and every file it runs, transitively, as repo-relative paths."""
    seen: set[str] = set()
    stack = [flow_rel]
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        path = root / current
        for ref in flow_refs(path):
            resolved = resolve_ref(ref, path, root)
            if resolved is None:
                continue
            rel = resolved.relative_to(root.resolve()).as_posix()
            # Every file the flow reads is part of its contract: a helper
            # script is content the qualification has to attest to as much as
            # a helper flow is.
            if rel not in seen:
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


def initial_set_digest(names: list[str]) -> str:
    """The digest that pins a frozen set of flow names.

    Sorted and newline-separated, so the digest does not depend on the order
    the names are written in.
    """
    payload = "".join(f"{name}\n" for name in sorted(names))
    return f"sha256:{hashlib.sha256(payload.encode('utf-8')).hexdigest()}"


def read_initial_set(path: pathlib.Path) -> list[str]:
    """The frozen set: the flows that were in blocking scopes at rule introduction.

    The set is a list of unique flow names. Anything else is refused: a set
    that cannot be read as a set of names is a set the gate cannot trust.
    """
    data = read_yaml(path)
    initial = data.get("initial_flows")
    if not isinstance(initial, list):
        raise ValueError(
            f"{path}: initial_flows is missing or not a list — the frozen set has to name "
            f"the flows that were in blocking scopes when the rule was introduced"
        )
    if not initial:
        # An empty frozen set admits nothing, which is safe: every flow in a
        # blocking scope then has to be qualified. It is refused nowhere,
        # because a rule that has just been introduced has no pre-rule flow.
        return []
    names = []
    for name in initial:
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"{path}: initial_flows must be a list of unique strings")
        names.append(name)
    if len(set(names)) != len(names):
        raise ValueError(f"{path}: initial_flows must be a list of unique strings")
    return sorted(names)


def _read_frozen_set(path: pathlib.Path) -> list[str]:
    """The frozen set, read for the CLI paths that work on it directly."""
    try:
        return read_initial_set(path)
    except (OSError, ValueError, yaml.YAMLError) as exc:
        raise click.ClickException(f"cannot read the frozen set {path}: {exc}") from exc


def verify_frozen_set(names: list[str], expected_digest: str | None) -> str:
    """Check the frozen set against its pinned digest.

    The frozen set is only as trustworthy as the lock on it. A set that does
    not match its pin is refused: a flow that nobody ever ran may have been
    added to it, and the gate would admit it as if it had been there when the
    rule was introduced.
    """
    digest = initial_set_digest(names)
    if expected_digest is not None and digest != expected_digest:
        raise ValueError(
            f"the frozen set does not match its pinned digest: expected {expected_digest}, "
            f"got {digest} — the frozen set only changes deliberately (update the pinned "
            f"digest in {pathlib.Path(__file__).name} and say in the change that flows are "
            f"admitted without being qualified)"
        )
    return digest


def baseline_digest(baseline: dict[str, str]) -> str:
    """The digest that pins a baseline map.

    Sorted by flow name, so the digest does not depend on the order the map
    is written in.
    """
    payload = "".join(
        f"{flow}\0{fingerprint}\n" for flow, fingerprint in sorted(baseline.items())
    )
    return f"sha256:{hashlib.sha256(payload.encode('utf-8')).hexdigest()}"


def verify_baseline(baseline: dict[str, str], expected_digest: str | None) -> str:
    """Check the baseline map against its pinned digest.

    The baseline is the record of the flow content the rule was introduced
    for. A map that does not match its pin is refused: an edited fingerprint
    is a changed flow that nobody qualified, and the gate would admit it as
    if it had been there since the beginning.
    """
    digest = baseline_digest(baseline)
    if expected_digest is not None and digest != expected_digest:
        raise ValueError(
            f"the baseline does not match its pinned digest: expected {expected_digest}, "
            f"got {digest} — the baseline only changes deliberately (update the pinned "
            f"digest in {pathlib.Path(__file__).name} and say in the change that flows are "
            f"admitted without being qualified)"
        )
    return digest


def read_baseline(path: pathlib.Path, initial_path: pathlib.Path | None = None) -> dict[str, str]:
    """The baseline map: flow -> fingerprint, for flows of the frozen set only.

    The baseline used to carry its own ``initial_flows`` list. It does not
    any more: a frozen set stored in the file it guards can be grown by
    editing that file, and a grown frozen set admits flows nobody ran. The
    frozen set lives in its own artifact now, pinned by a digest.
    """
    data = read_yaml(path)
    if "initial_flows" in data:
        where = initial_path.name if initial_path is not None else "the initial set artifact"
        raise ValueError(
            f"{path}: initial_flows does not belong in the baseline — the frozen set lives in "
            f"{where} and is pinned by a digest; a baseline that names its own frozen set is a "
            f"baseline that can admit new flows"
        )
    flows = data.get("flows") or {}
    if not isinstance(flows, dict):
        raise ValueError(f"{path}: flows is not a mapping")
    result: dict[str, str] = {}
    for flow, fp in flows.items():
        if isinstance(flow, str) and isinstance(fp, str):
            result[flow] = fp
    return result


def read_evidence(path: pathlib.Path) -> tuple[int, dict[str, dict], dict[str, dict]]:
    data = read_yaml(path)
    if "required_attempts" not in data:
        required = MIN_REQUIRED_ATTEMPTS
    else:
        required = data["required_attempts"]
        if type(required) is not int or required < 1:
            # An invalid policy is a configuration error, not a smaller
            # requirement: silently falling back to three would let a typo
            # decide what counts as a qualification.
            raise ValueError(
                f"{path}: required_attempts must be a positive integer, got {required!r}"
            )
        if required < MIN_REQUIRED_ATTEMPTS:
            # The evidence file may ask for more attempts than the floor, but
            # not for fewer: a qualification is a *repeated* run, and a file
            # that says one attempt admits a flow on its first green run --
            # the failure the rule was introduced to prevent.
            raise ValueError(
                f"{path}: required_attempts must be at least {MIN_REQUIRED_ATTEMPTS}, "
                f"got {required!r}"
            )
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
    # Same documented environment: kind, api, device model -- and the device
    # itself. Two runs on two devices of the same model are two runs on two
    # devices: the flakiness a qualification is meant to catch is often
    # device-specific, so the device is part of the identity.
    return (env.get("kind"), env.get("api"), env.get("device"), env.get("serial"))


def is_recognised_version(value: object) -> bool:
    """Whether a recorded version string is a version the engines recognise.

    The recognition is the runner's contract, read from the same place the
    runner reads it: `emulator.maestro._engine`. A record that names
    something the runner would have refused to record -- "Maestro version
    unavailable", a build number, a sentence -- is not a version, and a
    gate that accepted it would admit a record naming a build that never
    ran.
    """
    if not isinstance(value, str) or not value.strip():
        return False
    parsed = parse_engine_version(value)
    return parsed is not None and parsed == value.strip().removeprefix("v")


def attempt_admission_issues(attempt: dict, index: int) -> list[str]:
    """What one attempt has to record to count towards a qualification.

    The rules live here, once. The gate uses them to admit or refuse a
    record; the qualification runner uses the same ones to say why a
    record is not admissible *before* it writes one. A rule implemented
    twice is a rule that can drift -- and a rule that drifted is a rule
    the gate no longer holds.
    """
    issues: list[str] = []
    if not isinstance(attempt, dict):
        return [f"attempt {index} is not a mapping"]
    env = attempt.get("environment")
    if not isinstance(env, dict) or not env.get("kind") or env.get("api") is None:
        issues.append(f"attempt {index} does not record a documented environment (kind/api)")
    if not isinstance(env, dict) or not env.get("serial"):
        # The device is part of the identity. A run that does not name it
        # cannot be told apart from another run on another device.
        issues.append(f"attempt {index} does not record the device it ran on (serial)")
    # The APK that ran the attempt has to be this checkout's build. The
    # runner refuses to run a flow against a stale APK, and the gate
    # refuses to admit a record that says otherwise: the two values are
    # the checkout SHA and the build id read from the device, and a
    # qualification that names two different builds certifies a program
    # the checkout does not hold. The check is on the recorded values, so
    # it holds whatever produced them.
    #
    # It is a check on the *record*, not on the checkout the gate runs in:
    # the recorded build names the program the attempts certified. Whether
    # that program is still the one the repository holds is not this
    # gate's question -- the flow keeps running in the blocking suite,
    # where an application regression shows up as a red suite. ADR
    # ``docs/decisions/2026-11-25-qualification-currency-is-the-flow-not-the-build.md``.
    checkout_sha = attempt.get("commit")
    apk_sha = attempt.get("apk_commit_sha")
    if not checkout_sha or not apk_sha:
        issues.append(f"attempt {index} does not record both the checkout and the APK identity")
    elif checkout_sha != apk_sha:
        issues.append(
            f"attempt {index} ran an APK that is not the checkout build "
            f"({apk_sha} vs {checkout_sha})"
        )
    mv = attempt.get("maestro_version")
    if not mv:
        issues.append(f"attempt {index} does not record the Maestro version")
    elif not is_recognised_version(mv):
        # A version has to be a version: the same recognition the runner
        # applies before it writes one. Otherwise a record could name
        # "Maestro version unavailable" as the engine that ran the flow.
        issues.append(
            f"attempt {index} records a Maestro version that is not a "
            f"recognised version ({mv!r})"
        )
    return issues


def record_consistency_issues(record: dict) -> list[str]:
    """The rules about the record as a whole: one environment, one build, one version.

    A qualification is a run *in one place*: the attempts of one
    qualification have to share the device, the build and the engine
    version. The rules are here for the same reason the per-attempt ones
    are -- the gate and the runner apply the same contract.
    """
    issues: list[str] = []
    envs: set = set()
    builds: set = set()
    mversions: set = set()
    attempts = record.get("attempts")
    if not isinstance(attempts, list):
        return issues
    for attempt in attempts:
        if not isinstance(attempt, dict):
            continue
        env = attempt.get("environment")
        if isinstance(env, dict):
            envs.add(_env_key(env))
        builds.add(attempt.get("commit") or attempt.get("apk_commit_sha"))
        mversions.add(attempt.get("maestro_version"))
    if len(envs) > 1:
        issues.append("qualification attempts do not share one documented environment")
    if len(builds) > 1:
        issues.append("qualification attempts do not share one build identity")
    if len(mversions) > 1:
        issues.append("qualification attempts do not share one Maestro version")
    return issues


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
        issues = attempt_admission_issues(attempt, i)
        if issues:
            return False, issues[0]
    issues = record_consistency_issues(record)
    if issues:
        return False, issues[0]
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


def scan(
    root: pathlib.Path,
    catalogue_path: pathlib.Path,
    baseline_path: pathlib.Path,
    evidence_path: pathlib.Path,
    initial_path: pathlib.Path | None = None,
    expected_digest: str | None = FROZEN_SET_DIGEST,
    expected_baseline_digest: str | None = BASELINE_DIGEST,
) -> tuple[list[Finding], dict[str, int]]:
    initial_path = initial_path or DEFAULT_INITIAL
    try:
        catalogue = read_catalogue(catalogue_path)
    except OSError as exc:
        raise click.ClickException(f"cannot read catalogue {catalogue_path}: {exc}") from exc
    try:
        initial_names = read_initial_set(initial_path)
        verify_frozen_set(initial_names, expected_digest)
    except (OSError, ValueError, yaml.YAMLError) as exc:
        raise click.ClickException(f"cannot read the frozen set {initial_path}: {exc}") from exc
    initial_set = set(initial_names)
    try:
        baseline = read_baseline(baseline_path, initial_path) if baseline_path.is_file() else {}
    except (OSError, ValueError, yaml.YAMLError) as exc:
        raise click.ClickException(f"cannot read baseline {baseline_path}: {exc}") from exc
    if baseline_path.is_file():
        # The baseline is pinned: an edited map is a changed flow nobody
        # qualified. The gate refuses before it looks at a single flow.
        try:
            verify_baseline(baseline, expected_baseline_digest)
        except ValueError as exc:
            raise click.ClickException(f"cannot read baseline {baseline_path}: {exc}") from exc
    try:
        required, qualifications, exceptions = read_evidence(evidence_path)
    except (OSError, ValueError, yaml.YAMLError) as exc:
        raise click.ClickException(f"cannot read evidence {evidence_path}: {exc}") from exc

    flows, findings = blocking_scope_flows(catalogue, root)
    counts = {"flows": len(flows), "baselined": 0, "qualified": 0, "excepted": 0}

    # Every entry in the map must point at a flow that is still in the
    # catalogue. A fingerprint of a flow that was retired answers nothing
    # while the flow is gone, and admits it again — without a
    # qualification run — the moment it comes back with the same content.
    # Retiring a flow therefore drops its fingerprint in the same change
    # that removes it from the catalogue.
    for flow in sorted(set(baseline) - set(catalogue)):
        findings.append(
            Finding(
                flow,
                "orphaned baseline entry",
                f"{flow} — the baseline map holds a fingerprint for a flow that is not in the "
                "catalogue; a retired flow leaves the map, and BASELINE_DIGEST is refreshed "
                "in that change",
            )
        )

    for flow in flows:
        fp = fingerprint(flow, root)
        # A flow that reads content the tree does not cover cannot be admitted
        # by anything: the fingerprint says nothing about that content, and the
        # snapshot the attempts run does not contain it.
        untracked = dependency_issues(flow, root)
        if untracked:
            findings.append(
                Finding(
                    flow,
                    "untracked dependency",
                    "; ".join(untracked)
                    + f" — the flow reads content the qualification tree "
                    f"({QUALIFICATION_TREE}/) does not cover; keep every reference inside it and requalify",
                )
            )
            continue
        if flow in initial_set and baseline.get(flow) == fp:
            counts["baselined"] += 1
            stale = stale_exception_reason(exceptions.get(flow), fp) if flow in exceptions else None
            if stale is not None:
                findings.append(Finding(flow, "stale exception", stale))
            continue

        # Recovery for a changed/non-matching flow happens via qualification or
        # exception for the current fingerprint (including stale baseline
        # flows). The two are evaluated independently: a stale qualification
        # must not shadow a valid exception, and a failed qualification must
        # not hide the exception that admits the flow.
        qualification_reason = None
        if flow in qualifications:
            ok, reason = is_qualified(qualifications[flow], fp, required)
            if ok:
                counts["qualified"] += 1
                stale = stale_exception_reason(exceptions.get(flow), fp) if flow in exceptions else None
                if stale is not None:
                    findings.append(Finding(flow, "stale exception", stale))
                continue
            qualification_reason = reason
        exception_reason = None
        if flow in exceptions:
            ok, reason = is_excepted(exceptions[flow], fp)
            if ok:
                counts["excepted"] += 1
                continue
            exception_reason = reason
        if qualification_reason is not None:
            findings.append(Finding(flow, "not qualified", qualification_reason))
        if exception_reason is not None:
            findings.append(Finding(flow, "invalid exception", exception_reason))
        if qualification_reason is not None or exception_reason is not None:
            continue

        if flow in baseline and flow not in initial_set:
            findings.append(
                Finding(
                    flow,
                    "baseline expansion",
                    "the baseline map cannot admit a flow outside the frozen set; qualify it or add an evidence-backed exception",
                )
            )
        elif flow in initial_set:
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


def stale_exception_reason(record: dict, current_fp: str) -> str | None:
    """Why an exception that admitted nothing is a finding, if it is one.

    An exception is read as a reason a flow is admitted *instead* of being
    qualified. When the flow is admitted by something else, an exception that
    names a fingerprint the flow has left behind is a record about a flow that
    no longer exists: it says the flow was never qualified on a device while
    the file elsewhere says it was qualified three times. The record has to be
    reissued against the current fingerprint or deleted.
    """
    if not isinstance(record, dict):
        return "the exception record is not a mapping"
    recorded = record.get("fingerprint")
    if recorded == current_fp:
        return None
    return (
        f"the exception is for fingerprint {recorded}, current is {current_fp} "
        f"(reissue it for the changed flow, or delete it if the flow is qualified)"
    )


def build_baseline(root: pathlib.Path, catalogue_path: pathlib.Path, initial_names: list[str]) -> dict:
    """The baseline map for the frozen set.

    Only flows of the frozen set get a fingerprint: the baseline is what the
    frozen set admits, so a flow outside it has no place here. And the
    baseline is written once — a flow that changed is requalified, not
    re-baselined, so re-running this would quietly undo the rule.
    """
    catalogue = read_catalogue(catalogue_path)
    flows, findings = blocking_scope_flows(catalogue, root)
    missing = [f for f in findings if f.kind == "flow file missing"]
    if missing:
        raise click.ClickException("cannot build baseline: " + "; ".join(f.detail for f in missing))
    frozen = set(initial_names)
    mapping = {flow: fingerprint(flow, root) for flow in flows if flow in frozen}
    return {
        "schema": 1,
        "policy": "flows in automated blocking scopes at rule introduction; a matching fingerprint admits the flow, a change requires qualification",
        "flows": dict(sorted(mapping.items())),
    }


@click.command(context_settings={"help_option_names": ["-h", "--help"]})
@click.option("--json", "as_json", is_flag=True, help="Emit JSON output")
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit for findings")
@click.option("--root", type=click.Path(path_type=pathlib.Path), default=ROOT, show_default=False)
@click.option("--catalogue", type=click.Path(path_type=pathlib.Path), default=DEFAULT_CATALOGUE)
@click.option("--baseline", type=click.Path(path_type=pathlib.Path), default=DEFAULT_BASELINE)
@click.option("--initial", type=click.Path(path_type=pathlib.Path), default=DEFAULT_INITIAL, help="The frozen set of flows that were in blocking scopes at rule introduction.")
@click.option("--evidence", type=click.Path(path_type=pathlib.Path), default=DEFAULT_EVIDENCE)
@click.option("--init-baseline", is_flag=True, help="Write the baseline for the frozen set and exit")
@click.option("--init-evidence", is_flag=True, help="Write an empty evidence file if missing and exit")
@click.option("--print-initial-digest", is_flag=True, help="Print the digest that pins the frozen set and exit")
@click.option("--print-baseline-digest", is_flag=True, help="Print the digest that pins the baseline map and exit")
@click.pass_context
def cli(ctx: click.Context, as_json: bool, check: bool, root: pathlib.Path, catalogue: pathlib.Path, baseline: pathlib.Path, initial: pathlib.Path, evidence: pathlib.Path, init_baseline: bool, init_evidence: bool, print_initial_digest: bool, print_baseline_digest: bool) -> None:
    root = root.resolve()
    try:
        if print_initial_digest:
            names = _read_frozen_set(initial)
            click.echo(initial_set_digest(names))
            raise ctx.exit(CLEAN)
        if print_baseline_digest:
            try:
                flows = read_baseline(baseline, initial)
            except (OSError, ValueError, yaml.YAMLError) as exc:
                raise click.ClickException(f"cannot read the baseline {baseline}: {exc}") from exc
            click.echo(baseline_digest(flows))
            raise ctx.exit(CLEAN)
        if init_baseline:
            names = _read_frozen_set(initial)
            try:
                verify_frozen_set(names, FROZEN_SET_DIGEST)
            except ValueError as exc:
                raise click.ClickException(f"cannot build the baseline: {exc}") from exc
            if baseline.exists():
                raise click.ClickException(
                    f"{baseline} already exists — the baseline is written once; a flow that "
                    f"changed is requalified, not re-baselined"
                )
            data = build_baseline(root, catalogue, names)
            baseline.parent.mkdir(parents=True, exist_ok=True)
            baseline.write_text("# Maestro flow qualification baseline: fingerprints of the frozen set.\n# This map only shrinks; a changed flow must be qualified or excepted instead.\n" + yaml.safe_dump(data, sort_keys=False, allow_unicode=True), encoding="utf-8")
            click.echo(f"check-maestro-flow-qualification: wrote baseline for {len(data['flows'])} flow(s) to {baseline}")
            click.echo(f"check-maestro-flow-qualification: baseline digest {baseline_digest(data['flows'])}")
            raise ctx.exit(CLEAN)
        if init_evidence:
            if not evidence.exists():
                evidence.parent.mkdir(parents=True, exist_ok=True)
                evidence.write_text("# Maestro flow qualification evidence and exceptions.\n# A qualification requires required_attempts consecutive passing test attempts\n# in the same documented environment/build, with every attempt recorded.\n" + yaml.safe_dump({"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}}, sort_keys=False), encoding="utf-8")
                click.echo(f"check-maestro-flow-qualification: wrote empty evidence file to {evidence}")
            else:
                click.echo(f"check-maestro-flow-qualification: evidence file exists: {evidence}")
            raise ctx.exit(CLEAN)

        findings, counts = scan(root, catalogue, baseline, evidence, initial)
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
