"""Kiwi seed: project the scenario specs into Kiwi.

One plan, ``Scenarios``. Classification via ``Component`` (reusing the 48 that
already exist), ``Category`` and tags — **no nested plans as a product tree**:
``TestPlan`` in Kiwi is a campaign container, and this build exposes no
requirements tree at all (``Requirement.*`` is not in the RPC surface and
``TestCase.requirement`` is a free-text ``CharField(255)``), so the directory
hierarchy lives in Git and Kiwi is not asked to mirror it.

Idempotency is keyed on the scenario **id**, not the title: titles get edited,
ids do not, and keying on the title would fork a case and split its run history
every time someone improved the wording.

``--check`` is the only real enforcement of "do not edit in the UI". A field
changed in Kiwi is overwritten on the next seed, so a spec that drifts silently is
a spec nobody is reading; ``--check`` turns that into a visible failure. It
compares status, summary, priority, area and spec path — every field a person can
edit in the UI. Comparing only the status (as this did first) made it a false
green for everything else.
"""

from __future__ import annotations

import sys
from pathlib import Path

from traceability import REPO_ROOT
from traceability.spec import ScenarioSpec, SpecStatus

__all__ = ["PLAN_NAME", "run_seed", "seed_specs", "SCENARIO_CATEGORY", "STATUS_TO_KIWI"]

PLAN_NAME = "Scenarios"

#: Set by the seeder, not by the spec. A `category:` field in a spec would be
#: identical for every scenario — a constant masquerading as data.
SCENARIO_CATEGORY = "scenario"

#: Spec lifecycle → Kiwi TestCaseStatus. An explicit table rather than a
#: convention, because the two vocabularies differ and a silent fallback would
#: map a deprecated scenario onto an active one.
STATUS_TO_KIWI = {
    SpecStatus.PROPOSED: "PROPOSED",
    SpecStatus.CONFIRMED: "CONFIRMED",
    SpecStatus.DEPRECATED: "DISABLED",
}


def _kiwi_module():
    """Import the flat ``kiwi_client`` next to this package.

    Imported lazily so the pure core stays importable — and testable — with no
    stand, no TLS config and no XML-RPC stack in the process.
    """
    from traceability import kiwi_module

    return kiwi_module()


def _case_summary(spec: ScenarioSpec) -> str:
    """The Kiwi case text: the immutable id, then the title.

    The id leads because it is the stable part; a Kiwi search for
    ``TASK-REC-01`` should find the case regardless of how the title is worded.
    """
    return f"{spec.id} {spec.title}"


def _resolve_case(client, plan_id: int, spec: ScenarioSpec) -> dict | None:
    """Find an existing case by its scenario id, or ``None``.

    The id is stored in a case property rather than parsed back out of the
    summary, because the summary is editable text while a property is the key
    this system actually joins on.

    Note the reader: ``get_cases_properties`` takes **case** ids, while the
    similarly named ``get_all_cases_properties`` takes **plan** ids. Passing
    case ids to the latter is silent — it returns ``{}``, which reads exactly
    like "no such scenario" and makes every seed create a duplicate.
    """
    cases = client.get_cases(plan_id)
    if not cases:
        return None
    properties = client.get_cases_properties([case["id"] for case in cases])
    matches = [case for case in cases if properties.get(case["id"], {}).get("scenario_id") == spec.id]
    if len(matches) > 1:
        # Never delete here. A duplicate splits one scenario's history across
        # two cases, so it must be visible; removal is a human decision, which
        # is the standing project rule for anything irreversible in Kiwi.
        print(
            f"  ! {spec.id}: в плане {len(matches)} кейса "
            f"({', '.join(str(c['id']) for c in matches)}) — история прогона разорвана, "
            f"дубли нужно удалить вручную"
        )
    return matches[0] if matches else None


def seed_specs(specs: dict[str, ScenarioSpec], dry_run: bool = False, check: bool = False) -> int:
    """Create or verify one Kiwi case per scenario. Returns a process exit code.

    ``dry_run`` and ``check`` are different questions and both are useful:
    dry-run asks "what *would* change", check asks "has something *already*
    drifted". Collapsing them would mean a CI check that cannot tell an
    expected change from an unwanted one.
    """
    kiwi_client = _kiwi_module()
    client = kiwi_client.KiwiClient()
    client.login()

    product = client.get_product("Singularity Todo")
    if product is None:
        raise RuntimeError("продукт 'Singularity Todo' не найден на стенде")
    product_id = product["id"]

    plan = client.get_plan(product_id, PLAN_NAME)
    if plan is None:
        if dry_run or check:
            print(f"план '{PLAN_NAME}' отсутствует — будет создано при seed")
            return 1 if check else 0
        plan = client.create_plan(product_id, PLAN_NAME)
    plan_id = plan["id"]

    created = reused = repaired = 0
    drift: list[str] = []
    for spec_id in sorted(specs):
        spec = specs[spec_id]
        existing = _resolve_case(client, plan_id, spec)
        expected_status = STATUS_TO_KIWI[spec.status]

        if existing is None:
            print(f"  + создать {spec.id} {spec.title!r}")
            created += 1
            if dry_run or check:
                continue
            component = client.get_component(product_id, spec.area) or client.create_component(
                product_id, spec.area
            )
            case = client.create_case(
                product_id=product_id,
                plan_id=plan_id,
                summary=_case_summary(spec),
                component_id=component["id"],
                priority=spec.priority,
                category=SCENARIO_CATEGORY,
                # Lifecycle travels with the case, not only as a tag: a tag is
                # cosmetic, the status is what Kiwi filters and reports on.
                case_status=expected_status,
            )
            client.link_case_to_plan(plan_id, case["id"])
            # The id is the idempotency key; extra_link points a human in Kiwi
            # back to the canonical spec, which is what makes "do not edit here"
            # actionable rather than merely asserted.
            client.set_property(case["id"], "scenario_id", spec.id)
            client.set_property(case["id"], "spec_path", _spec_path(spec))
            client.set_property(case["id"], "area", spec.area)
            client.set_tag(case["id"], f"scenario:{spec.id}")
            client.set_tag(case["id"], f"status:{expected_status.lower()}")
            continue

        reused += 1
        # Compare every field a person can change in the Kiwi UI, not just the
        # status. Checking one field made `--check` a false green: a UI edit to
        # the title, the priority or the component was neither reverted nor
        # reported, and a spec edit to any of them never reached Kiwi at all —
        # which is precisely the drift the check exists to catch.
        actual = client.get_case(existing["id"])
        # `case_status__name`, not `status`: TestCase.filter returns the status
        # as an id, and reading `status` yields None — which then looks exactly
        # like drift on every single case, forever.
        if actual.get("case_status__name") != expected_status:
            drift.append(
                f"{spec.id}: статус в Kiwi '{actual.get('case_status__name')}', "
                f"в спеке '{expected_status}'"
            )
        expected_summary = _case_summary(spec)
        if actual.get("summary") != expected_summary:
            drift.append(
                f"{spec.id}: summary в Kiwi '{actual.get('summary')}', в спеке '{expected_summary}'"
            )
        if actual.get("priority__value") != spec.priority:
            drift.append(
                f"{spec.id}: приоритет в Kiwi '{actual.get('priority__value')}', в спеке '{spec.priority}'"
            )
        # The area lives in a property because TestCase.filter does not return
        # component__name (see kiwi_client.create_case's companion note).
        actual_props = client.get_case_properties(existing["id"])
        if actual_props.get("area") != spec.area:
            drift.append(
                f"{spec.id}: область в Kiwi '{actual_props.get('area')}', в спеке '{spec.area}'"
            )
        if actual_props.get("spec_path") != _spec_path(spec):
            drift.append(
                f"{spec.id}: spec_path в Kiwi '{actual_props.get('spec_path')}', "
                f"в спеке '{_spec_path(spec)}'"
            )
        if check or dry_run:
            continue
        # Repair what is repairable. Summary, priority and case status are NOT
        # repairable: this RPC surface has no TestCase.update, so a change to
        # those is reported by --check and must be fixed by hand. Silently
        # leaving them would be the same false green this check just closed.
        for name, value in (
            ("scenario_id", spec.id),
            ("spec_path", _spec_path(spec)),
            ("area", spec.area),
        ):
            if actual_props.get(name) != value:
                client.set_property(existing["id"], name, value)
                repaired += 1
        client.set_tag(existing["id"], f"scenario:{spec.id}")

    if check:
        if created or drift:
            print(f"✗ Kiwi расходится со спеками: не создано={created}, расхождений={len(drift)}")
            for line in drift:
                print(f"    {line}")
            return 1
        print(f"✓ Kiwi совпадает со спеками: {reused} кейсов")
        return 0

    if not dry_run and repaired:
        # Git → Kiwi is one direction and the spec wins, so a normal seed
        # repairs what it can. Properties are get-or-create by name
        # (TestCase.add_property), so writing them converges the stand instead
        # of leaving a permanent drift that only a manual fix could clear.
        print(f"✓ создано {created}, переиспользовано {reused}, восстановлено свойств: {repaired}")
    else:
        print(f"(dry-run) создать {created}, переиспользовано {reused}")
    for line in drift:
        print(f"  ! {line}")
    return 0


def _spec_path(spec: ScenarioSpec) -> str:
    """Repo-relative spec path.

    Absolute paths would make the stand describe one developer's checkout and
    render the link useless for the next person, so the path is relativised
    against the repository root the way every other artefact here is.
    """
    if spec.path is None:
        return ""
    try:
        return spec.path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return spec.path.as_posix()


def run_seed(specs: dict[str, ScenarioSpec], dry_run: bool = False, check: bool = False) -> int:
    """CLI entry: seed, translating a missing stand into a distinct exit code.

    A stand outage is not a spec error, and conflating them would make a
    restarted container look like a broken repository.
    """
    kiwi_client = _kiwi_module()
    try:
        return seed_specs(specs, dry_run=dry_run, check=check)
    except kiwi_client.KiwiError as exc:
        print(f"✗ стенд Kiwi недоступен: {exc}", file=sys.stderr)
        print("  покрытие и матрица результатов уже записаны; публикация не является гейтом", file=sys.stderr)
        return 3
