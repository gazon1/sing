"""``python -m traceability carrier <ID>`` — write the reachability probe.

The bottleneck in the carrier queue is not writing an assertion, it is finding
out **whether a tier can reach the node at all**. Measured on this repository's
own corpus: ``AUTH-FIRSTRUN-01`` cost one probe, and ``TASK-TIME-01`` cost a
failed commit plus a wrong decision, because a failed probe was read as a fact
about the platform rather than about the code in front of it.

So the probe is a generator, not a paragraph. Sixteen dark scenarios times a
hand-written probe is sixteen chances to write it slightly differently, and the
differences that matter are the ones nobody would notice:

* ``onNodeWithTag`` and ``onRoot()`` both **throw** when the composition has two
  semantics roots, so a probe written with them reports "ambiguous node" and
  sends the next person hunting a semantics bug that is not there. The plural
  selector cannot fail that way, so its failure means one thing only.
* The ``@DisplayName`` id must be the **first token**. A probe whose id sits
  mid-string validates and then joins nothing, and the symptom is an empty
  matrix rather than an error.
* ``@Tag("slow")`` is mandatory, or the class is excluded from the default local
  cycle and the scenario reads as "never run" forever.

What this deliberately does **not** do is decide whether the target can assert
the behaviour. The probe answers one question and the answer is written down in
the generated file's header, because a probe that quietly encodes a conclusion is
how ``TASK-TIME-01`` got narrowed to Android in the first place.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from traceability.keys import TestKey
from traceability.spec import ScenarioSpec, Target

__all__ = ["CarrierPlan", "plan_carrier", "render_probe", "PROBE_WARNING"]

#: Where each target's carrier lives, and what a Kotlin test there can and
#: cannot reach. The android row is a Maestro flow, not a Compose test, because a
#: JVM test cannot drive a device — the tier split is structural, not a choice.
CARRIER_DIR: dict[Target, str] = {
    Target.DESKTOP: "desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows",
    Target.ANDROID: "androidApp/src/androidTest/kotlin/com/singularity/todo/feature/flows",
}

PROBE_WARNING = """\
## Read this before believing a failure

A failed probe means **the node is not in the tree you just rendered**. It does
not mean the feature is Android-only, and narrowing the spec to one target on
the strength of it is how `TASK-TIME-01` was made wrong on 2026-10-05: the whole
feature was in `commonMain` and the desktop screen simply was not rendering it,
because a merge (`f2a87c7c`) had put the two platform graphs on different
screens.

Before concluding anything, check where the feature lives:

    find shared/src/commonMain -iname '*<feature>*'

If it is in `commonMain`, a missing node is a **hole in the code**, not a
property of the platform. Fix the code and keep both targets claimed. Only a
structural difference justifies narrowing — `ModalBottomSheet` on desktop, which
is a separate *window* and whose tree is byte-identical before and after a click.
"""


class CarrierError(Exception):
    """The request cannot be served as asked — never a silent no-op."""


@dataclass(frozen=True, slots=True)
class CarrierPlan:
    """What would be written, and where — computed, not decided at write time."""

    scenario: str
    target: Target
    path: Path
    class_name: str
    display_name: str
    test_name: str
    doc: str

    @property
    def exists(self) -> bool:
        return self.path.exists()


def _class_name(scenario: str) -> str:
    """``TASK-TIME-01`` -> ``TaskTime01ScenarioTest``.

    Deliberately not a title-case of the whole id: the numeric segment stays
    numeric because ``TASK-TIME-01`` and a future ``TASK-TIME-02`` must land in
    *different* files, and ``TaskTimeScenarioTest`` would be the same name twice.
    """
    parts = [p for p in re.split(r"[-_]", scenario) if p]
    words = [p if p.isdigit() else p.capitalize() for p in parts]
    return "".join(words) + "ScenarioTest"


def _test_name(display_name: str) -> str:
    """A Kotlin ``fun`` name that does not repeat the id already in @DisplayName."""
    body = display_name.split(" ", 1)[1] if " " in display_name else display_name
    slug = re.sub(r"[^a-z0-9]+", "_", body.lower()).strip("_")
    # The display name already opens with "probe:", so prefixing again produces
    # `probe_probe_the_surface_is_reachable`. Stripped rather than avoided,
    # because the display name is the part the join reads and the method name is
    # the part nobody looks at — but a doubled word in a generated file reads as
    # a generator bug and undermines the rest of the file.
    slug = re.sub(r"^probe_", "", slug)
    return f"probe_{slug}" if slug else "probe_is_reachable"


def _doc(spec: ScenarioSpec, target: Target) -> str:
    claimed = ", ".join(t.value for t in spec.targets)
    return "\n".join(
        [
            f"Desktop automation for scenario `{spec.id}` — see",
            f"`infra/kiwi/scenarios/{spec.path.relative_to(spec.path.parents[2])}`.",
            "",
            f"The scenario claims: {claimed}.",
            "",
            "This file is a **reachability probe**, not the scenario's assertion: it",
            "answers one question — can this tier reach the node at all — and the",
            "answer is yours to interpret.",
            "",
            PROBE_WARNING,
            "## The linkage",
            "",
            "The `@DisplayName` id must stay the **first token**. JUnit writes the",
            "display name into `<testcase name>`, and the scanner joins on exactly",
            "that, so an id anywhere else validates and then joins nothing.",
            "",
            "`@Tag(\"slow\")` is not optional: without it the class is excluded from",
            "the default local cycle and the scenario reads as never run forever.",
        ]
    )


def plan_carrier(spec: ScenarioSpec, target: Target, root: Path) -> CarrierPlan:
    """Compute the file this carrier would occupy. Pure — writes nothing."""
    if spec.path is None:  # pragma: no cover - load_specs always sets it
        raise CarrierError(f"{spec.id} has no source path; cannot place a carrier")
    subarea = spec.path.parent.name
    class_name = _class_name(spec.id)
    display_name = f"{spec.id} probe: the surface is reachable"
    return CarrierPlan(
        scenario=spec.id,
        target=target,
        path=root / CARRIER_DIR[target] / subarea / f"{class_name}.kt",
        class_name=class_name,
        display_name=display_name,
        test_name=_test_name(display_name),
        doc=_doc(spec, target),
    )


_DESKTOP_TEMPLATE = """\
package com.singularity.todo.feature.flows.{subarea}

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
{doc_lines}
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class {class_name} {{

    @Test
    @DisplayName("{display_name}")
    fun {test_name}() = runDesktopAppTest(checkA11y = true) {{
        // Navigate exactly as the real test would. A probe that does not
        // reproduce the navigation proves nothing about the node — it only
        // proves you are on the wrong screen.
        //
        // `onAllNodesWithTag`, not `onNodeWithTag`: the singular selector and
        // `onRoot()` both throw when the composition has two semantics roots,
        // and that failure reads as an ambiguous node rather than as "not
        // there". The plural selector cannot fail that way.
        awaitTag(TARGET_TAG).assertIsDisplayed()
    }}
}}

/** Replace with the node this scenario is about. See the header for what a
 *  failure here does and does not mean. */
private const val TARGET_TAG = "REPLACE_ME"
"""


def render_probe(plan: CarrierPlan) -> str:
    """The probe file's text. Deterministic — the same plan always renders the same bytes."""
    if plan.target is not Target.DESKTOP:
        raise CarrierError(
            f"the android tier is a Maestro flow, not a Compose test: write a flow "
            f"tagged `scenario:{plan.scenario}` under Maestro/flows/ instead"
        )
    return _DESKTOP_TEMPLATE.format(
        subarea=plan.path.parent.name,
        doc_lines="\n".join(f" * {line}" if line else " *" for line in plan.doc.splitlines()),
        class_name=plan.class_name,
        display_name=plan.display_name,
        test_name=plan.test_name,
    )


def key_of(plan: CarrierPlan) -> TestKey:
    """The join key this carrier will produce, so the caller can show it."""
    return TestKey(fqcn=plan.class_name, method=plan.display_name)
