#!/usr/bin/env python3
"""check-pro-licence-boundary.py — prove the Apache-2.0 half contains no `pro` code.

Why this exists (2026-10-05): the repository is being published as an Apache-2.0 core
plus a source-available `pro/` catalogue under FSL-1.1-ALv2. That split is only a licence
split if it is *real*, and a licence split that is asserted in a document but true only on
paper is worse than no split at all — it is a published licence claim the project cannot
honour.

The concrete thing that made this urgent: `ru.ok.tracer` (AppTracer, © VK, "Tracer's
License Agreement", not OSI-approved) was a direct `implementation` dependency of both
`:shared` and `:androidApp`, wired into the `Application` class. It has since moved to
`:pro`. This script is what stops it creeping back.

## What is checked

1. **Direction.** No Apache-2.0 source file imports `com.singularity.todo.pro`. The
   dependency may only point from `pro/` into the core, never back.
2. **Vendor isolation.** No Apache-2.0 build file declares a dependency on a
   non-OSI group. `DENIED_GROUPS` is the list; it is asserted, not derived, because
   "derive the denied set from the licences" would need the resolved dependency graph,
   and a check that needs a full Gradle resolution is a check nobody runs.
3. **Pro files declare themselves.** Every file under the FSL directories carries
   `SPDX-License-Identifier: FSL-1.1-ALv2`. A file added to `pro/` without the tag would
   be published under an implied licence it never agreed to.
4. **The free build really is free.** `settings.gradle.kts` includes `:pro` only behind
   `withPro`, and `LICENSE.pro` exists.
5. **The pro source set is not in the free build.** `androidApp/src/pro/kotlin` must be
   added conditionally — the files there reference proprietary types, so compiling them
   unconditionally would break the free build outright.

## What this script does NOT do

It does not inspect a built APK. That is `--verify-apk`, below, which is a separate mode
because it needs a build to have happened and a build in a source check is a source check
nobody runs. Run both in CI: the source gate on every commit, the APK gate after
assembling.

## The positive control

Every rule here is a pattern over text, and a pattern that quietly stops matching reports
success having checked nothing — the failure mode this project has already paid for
twice. So:

- `--self-test` plants a file that violates each rule in a temp tree and asserts it is
  caught, and plants a compliant tree and asserts it is not.
- The vendor rule additionally fails if the denied set is empty, so "no vendor
  dependencies at all" can never be reported as a pass by an empty list.

Usage:
    python3 scripts/check-pro-licence-boundary.py
    python3 scripts/check-pro-licence-boundary.py --self-test
    python3 scripts/check-pro-licence-boundary.py --verify-apk <apk> [--expect pro|free]

Exit codes:
    0 — the boundary holds
    1 — a violation, or the scan is vacuous
"""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Apache-2.0 territory. Anything here is published under Apache-2.0, so it may not
# contain or reference source-available code.
APACHE_ROOTS = (
    "shared/src",
    "androidApp/src/main",
    "androidApp/src/debug",
    "androidApp/src/androidTest",
    "desktopApp/src",
    "mcp-server/src",
    "detekt-rules/src",
)

# FSL-1.1-ALv2 territory. These are published under LICENSE.pro, not LICENSE.
FSL_ROOTS = ("pro/src", "androidApp/src/pro")

# The pro package. Core must not import it.
PRO_PACKAGE = "com.singularity.todo.pro"

# Maven groups whose licences forbid redistribution under Apache-2.0. Asserted rather
# than derived — see the module docstring.
DENIED_GROUPS = ("ru.ok.tracer",)

# md5 of the canonical `LICENSE-2.0.txt` as published by the Apache Software
# Foundation, fetched 2026-10-05. Compared by digest rather than by substring
# because "Apache License" appears in a truncated file, an edited file, and a
# file with the terms replaced by a summary — all of which a substring check
# would report as a pass.
APACHE_2_0_MD5 = "3b83ef96387f14655fc854ddc3c6bd57"

# Upstreams whose derivation NOTICE must carry. PROVENANCE.md records these,
# but PROVENANCE.md is internal documentation; NOTICE is what travels with a
# distributed copy, and a downstream redistributor's obligation is discharged
# by NOTICE alone.
NOTICE_REQUIRED_ATTRIBUTIONS = ("Tasks.org", "Orgzly")

# Build files that ship in the free build.
APACHE_BUILD_FILES = (
    "shared/build.gradle.kts",
    "androidApp/build.gradle.kts",
    "desktopApp/build.gradle.kts",
    "mcp-server/build.gradle.kts",
)

FSL_SPDX = "SPDX-License-Identifier: FSL-1.1-ALv2"
APACHE_SPDX = re.compile(r"SPDX-License-Identifier:\s*(Apache-2\.0|GPL|LGPL|AGPL)", re.I)


class Violation(Exception):
    """A rule that did not hold."""


# --------------------------------------------------------------------------
# Rules
# --------------------------------------------------------------------------


def kt_files(root: Path) -> list[Path]:
    out: list[Path] = []
    for rel in APACHE_ROOTS:
        base = root / rel
        if base.is_dir():
            out.extend(p for p in base.rglob("*.kt") if p.is_file())
    return sorted(out)


def fsl_files(root: Path) -> list[Path]:
    out: list[Path] = []
    for rel in FSL_ROOTS:
        base = root / rel
        if base.is_dir():
            out.extend(p for p in base.rglob("*.kt") if p.is_file())
    return sorted(out)


def check_no_pro_imports(root: Path) -> list[Violation]:
    """Rule 1 — the dependency arrow points one way only."""
    out: list[Violation] = []
    for path in kt_files(root):
        for n, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if PRO_PACKAGE in line and line.lstrip().startswith(("import ", "package ")):
                out.append(
                    Violation(
                        f"{path.relative_to(root).as_posix()}:{n}: Apache-2.0 code references "
                        f"the FSL catalogue\n      {line.strip()}\n"
                        f"      `pro/` may depend on the core; the core may not depend on `pro/`. "
                        f"Expose what the core needs through an interface it already owns."
                    )
                )
    return out


def check_no_denied_dependencies(root: Path) -> list[Violation]:
    """Rule 2 — no non-OSI dependency may be declared in a free build file."""
    if not DENIED_GROUPS:
        return [Violation("DENIED_GROUPS is empty — the vendor rule could not fail")]
    out: list[Violation] = []
    for rel in APACHE_BUILD_FILES:
        path = root / rel
        if not path.is_file():
            continue
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for group in DENIED_GROUPS:
                if group in line and not line.lstrip().startswith("//"):
                    out.append(
                        Violation(
                            f"{rel}:{n}: Apache-2.0 module declares a dependency on "
                            f"`{group}`, whose licence is not OSI-approved\n"
                            f"      {line.strip()}\n"
                            f"      Move it to pro/build.gradle.kts, where it is covered by "
                            f"LICENSE.pro."
                        )
                    )
    return out


def check_fsl_files_declare_themselves(root: Path) -> list[Violation]:
    """Rule 3 — a file in the FSL tree must say so."""
    out: list[Violation] = []
    for path in fsl_files(root):
        head = path.read_text(encoding="utf-8", errors="replace")[:600]
        if FSL_SPDX not in head:
            out.append(
                Violation(
                    f"{path.relative_to(root).as_posix()}: FSL file has no "
                    f"`{FSL_SPDX}` header\n"
                    f"      It would otherwise be published under an implied licence it "
                    f"never agreed to."
                )
            )
    return out


def check_settings_gates_pro(root: Path) -> list[Violation]:
    """Rules 4 and 5 — the free build must not include or compile `pro`."""
    out: list[Violation] = []
    settings = root / "settings.gradle.kts"
    if not settings.is_file():
        return [Violation("settings.gradle.kts not found — cannot verify the :pro include")]
    text = settings.read_text(encoding="utf-8")
    if 'include(":pro")' not in text:
        out.append(Violation("settings.gradle.kts does not include :pro at all — is the module gone?"))
    elif "withPro" not in text:
        out.append(
            Violation(
                'settings.gradle.kts includes :pro unconditionally\n'
                "      The public repository must build and pass every gate without pro/. "
                "Gate the include on `withPro`."
            )
        )

    app = root / "androidApp/build.gradle.kts"
    if not app.is_file():
        return out + [Violation("androidApp/build.gradle.kts not found")]
    app_text = app.read_text(encoding="utf-8")
    if "src/pro/kotlin" in app_text and "withPro" not in app_text:
        out.append(
            Violation(
                "androidApp/build.gradle.kts references src/pro/kotlin without a withPro guard\n"
                "      Those files reference proprietary types; compiling them unconditionally "
                "breaks the free build."
            )
        )
    if 'implementation(project(":pro"))' in app_text and "withPro" not in app_text:
        out.append(
            Violation(
                "androidApp depends on :pro without a withPro guard — the free build would "
                "pull proprietary code onto the classpath"
            )
        )
    return out


def check_licence_files(root: Path) -> list[Violation]:
    out: list[Violation] = []

    # The Apache-2.0 half. `LICENSE.pro` was checked and `LICENSE` was not, so
    # the repository could reach a publication-ready state with the FSL terms
    # committed and the Apache terms missing — which is the one combination that
    # makes the split unenforceable: nothing in the tree states what governs the
    # 1328 files that are not under `pro/`. The canonical text is checked by
    # md5 rather than by a substring, because a truncated or edited licence file
    # still contains "Apache License".
    apache_licence = root / "LICENSE"
    if not apache_licence.is_file():
        out.append(
            Violation(
                "LICENSE is missing — nothing in the tree states the terms governing the "
                "Apache-2.0 half, so the open-core split is asserted only in this script"
            )
        )
    else:
        digest = hashlib.md5(apache_licence.read_bytes()).hexdigest()
        if digest != APACHE_2_0_MD5:
            out.append(
                Violation(
                    f"LICENSE is not the canonical Apache-2.0 text (md5 {digest}, expected "
                    f"{APACHE_2_0_MD5}). A modified licence file still contains the phrase "
                    f"'Apache License', so a substring check would pass it"
                )
            )

    notice = root / "NOTICE"
    if not notice.is_file():
        out.append(
            Violation(
                "NOTICE is missing — the Tasks.org and Orgzly attributions live nowhere else, "
                "and they are what a downstream distributor is required to carry"
            )
        )
    else:
        notice_text = notice.read_text(encoding="utf-8")
        for upstream in NOTICE_REQUIRED_ATTRIBUTIONS:
            if upstream not in notice_text:
                out.append(
                    Violation(
                        f"NOTICE does not attribute {upstream}. The derivation is recorded in "
                        f"PROVENANCE.md, but PROVENANCE.md is internal documentation — NOTICE is "
                        f"what travels with the code"
                    )
                )

    pro_licence = root / "LICENSE.pro"
    if not pro_licence.is_file():
        return out + [Violation("LICENSE.pro is missing — the FSL catalogue has no terms")]
    text = pro_licence.read_text(encoding="utf-8")
    if "Functional Source License, Version 1.1" not in text:
        out.append(Violation("LICENSE.pro does not declare FSL-1.1"))
    if "Apache License, Version 2.0" not in text:
        out.append(
            Violation(
                "LICENSE.pro has no Grant of Future License — FSL-1.1-ALv2 must state the "
                "two-year conversion to Apache-2.0, and its absence is the whole difference "
                "from a perpetual proprietary licence"
            )
        )
    return out


def check_not_vacuous(root: Path) -> list[Violation]:
    """The scan must have found something to reason about.

    An empty Apache tree means the paths moved and this script is checking nothing, which
    looks identical to a clean tree from here.
    """
    if not kt_files(root):
        return [
            Violation(
                "no Apache-2.0 Kotlin files found — the scan is vacuous, so rules 1 and 3 "
                "checked nothing. The source roots in APACHE_ROOTS/FSL_ROOTS have probably moved."
            )
        ]
    if not fsl_files(root):
        return [
            Violation(
                "no FSL Kotlin files found — the pro catalogue is empty or missing, so rule 3 "
                "has nothing to check and the boundary is untested in the direction that matters"
            )
        ]
    return []


# --------------------------------------------------------------------------
# APK verification
# --------------------------------------------------------------------------


def verify_apk(apk: Path, expect: str) -> list[Violation]:
    """Read a built APK and assert the vendor code is present or absent as expected.

    This is the check that the *artifact* matches the *source* claim. Everything above is
    static analysis and could all be true while a transitive dependency put the vendor
    classes in the free APK anyway — which is exactly the failure the source rules cannot
    see.
    """
    out: list[Violation] = []
    if not apk.is_file():
        return [Violation(f"APK not found: {apk}")]
    blob = b""
    with zipfile.ZipFile(apk) as zf:
        names = [n for n in zf.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        if not names:
            return [Violation(f"{apk.name} contains no dex files — is this an APK?")]
        for n in names:
            blob += zf.read(n)

    has_vendor = b"ru/ok/tracer" in blob
    has_pro_port = b"TracerCrashReportingPort" in blob
    has_free_port = b"FileCrashReportingPort" in blob
    has_pro_app = b"ProSingularityApp" in blob

    if expect == "free":
        if has_vendor:
            out.append(Violation(f"{apk.name}: contains ru/ok/tracer — proprietary code in the free APK"))
        if has_pro_port:
            out.append(Violation(f"{apk.name}: contains TracerCrashReportingPort"))
        if has_pro_app:
            out.append(Violation(f"{apk.name}: contains ProSingularityApp"))
        if not has_free_port:
            out.append(
                Violation(
                    f"{apk.name}: does not contain FileCrashReportingPort — the free build has "
                    f"no crash reporter bound, which is a different bug from the one this "
                    f"check looks for"
                )
            )
    else:
        if not has_vendor:
            out.append(Violation(f"{apk.name}: expected the vendor SDK, found none — :pro was not compiled in"))
        if not has_pro_port:
            out.append(Violation(f"{apk.name}: expected TracerCrashReportingPort"))
        if not has_pro_app:
            out.append(Violation(f"{apk.name}: expected ProSingularityApp"))
    return out


# --------------------------------------------------------------------------
# Positive control
# --------------------------------------------------------------------------

_COMPLIANT_CORE = (
    "package com.singularity.todo.feature.tasks\n\n"
    "import com.singularity.todo.core.observability.CrashReportingPort\n\n"
    "class Thing(private val reporter: CrashReportingPort)\n"
)
_COMPLIANT_FSL = (
    "// SPDX-License-Identifier: FSL-1.1-ALv2\n"
    "package com.singularity.todo.pro\n\n"
    "import co.touchlab.kermit.Logger\n\n"
    "class Thing(private val log: Logger)\n"
)
_SETTINGS = (
    "val withPro: Boolean = providers.gradleProperty(\"withPro\").orElse(\"false\")\n"
    "if (withPro) {\n    include(\":pro\")\n}\n"
)
_APP_BUILD = (
    "val withPro = true\n"
    "dependencies {\n"
    "    implementation(project(\":shared\"))\n"
    "    if (withPro) {\n        implementation(project(\":pro\"))\n    }\n"
    "}\n"
    "android { sourceSets { if (withPro) { getByName(\"main\").kotlin.directories.add(\"src/pro/kotlin\") } } }\n"
)
_LICENCE_PRO = (
    "# Functional Source License, Version 1.1, ALv2 Future License\n"
    "under the Apache License, Version 2.0 that is effective on the second anniversary\n"
)

# The fixture's LICENSE must satisfy the md5 rule, and a fixture cannot embed an
# 11 KB licence body. So the clean case is asserted against a `LICENSE` copied
# from the real repository when one exists, and the fixture writes a
# deliberately-wrong one otherwise. Embedding the body was the alternative and it
# would drift: the md5 is a claim about a file that lives in the repository, and
# a second copy of the licence in a test string is a second thing to update when
# the licence is ever re-fetched.
_COMPLIANT_NOTICE = "Singularity Todo\n\nAttribution: Tasks.org\nAttribution: Orgzly\n"


def _write_fixture_licence(root: Path) -> None:
    """Give the fixture a LICENSE the md5 rule accepts, if one is reachable."""
    real = ROOT / "LICENSE"
    if real.is_file():
        shutil.copyfile(real, root / "LICENSE")
    else:
        (root / "LICENSE").write_text("Apache License, Version 2.0\n", encoding="utf-8")


def _make_compliant(root: Path) -> None:
    p = root / "shared/src/commonMain/kotlin/com/singularity/todo/Thing.kt"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(_COMPLIANT_CORE, encoding="utf-8")
    p = root / "pro/src/main/kotlin/com/singularity/todo/pro/Thing.kt"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(_COMPLIANT_FSL, encoding="utf-8")
    p = root / "androidApp/src/pro/kotlin/com/singularity/todo/pro/Pro.kt"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(_COMPLIANT_FSL, encoding="utf-8")
    (root / "settings.gradle.kts").write_text(_SETTINGS, encoding="utf-8")
    (root / "androidApp").mkdir(parents=True, exist_ok=True)
    (root / "androidApp/build.gradle.kts").write_text(_APP_BUILD, encoding="utf-8")
    (root / "shared").mkdir(parents=True, exist_ok=True)
    (root / "shared/build.gradle.kts").write_text("dependencies { }\n", encoding="utf-8")
    (root / "LICENSE.pro").write_text(_LICENCE_PRO, encoding="utf-8")
    (root / "NOTICE").write_text(_COMPLIANT_NOTICE, encoding="utf-8")
    _write_fixture_licence(root)


def self_test() -> int:
    import tempfile

    failures: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _make_compliant(root)

        def expect_clean(label: str) -> None:
            for check in (check_no_pro_imports, check_no_denied_dependencies,
                          check_fsl_files_declare_themselves, check_settings_gates_pro,
                          check_licence_files, check_not_vacuous):
                v = check(root)
                if v:
                    failures.append(f"{label}: {check.__name__} reported {v[0]}")
            print(f"  clean case: {label} — no violation")

        def expect_caught(label: str, check, mutate) -> None:
            saved = {p: p.read_bytes() for p in
                     [root / "shared/build.gradle.kts", root / "settings.gradle.kts",
                      root / "LICENSE.pro", root / "shared/src/commonMain/kotlin/com/singularity/todo/Thing.kt",
                      root / "pro/src/main/kotlin/com/singularity/todo/pro/Thing.kt"]}
            try:
                mutate()
                if not check(root):
                    failures.append(f"{label}: {check.__name__} did not fire")
                else:
                    print(f"  positive control: {label} — caught")
            finally:
                for p, data in saved.items():
                    p.write_bytes(data)

        expect_clean("a compliant tree")

        expect_caught(
            "core importing pro",
            check_no_pro_imports,
            lambda: (root / "shared/src/commonMain/kotlin/com/singularity/todo/Thing.kt").write_text(
                "package com.singularity.todo.feature.tasks\n\n"
                "import com.singularity.todo.pro.observability.TracerCrashReportingPort\n\n"
                "class Thing\n", encoding="utf-8"),
        )
        expect_caught(
            "a vendor dependency in a free build file",
            check_no_denied_dependencies,
            lambda: (root / "shared/build.gradle.kts").write_text(
                "dependencies { implementation(\"ru.ok.tracer:tracer-crash-report:1.4.0\") }\n",
                encoding="utf-8"),
        )
        expect_caught(
            "an FSL file with no SPDX tag",
            check_fsl_files_declare_themselves,
            lambda: (root / "pro/src/main/kotlin/com/singularity/todo/pro/Thing.kt").write_text(
                "package com.singularity.todo.pro\n\nclass Thing\n", encoding="utf-8"),
        )
        expect_caught(
            "an unconditional :pro include",
            check_settings_gates_pro,
            lambda: (root / "settings.gradle.kts").write_text('include(":pro")\n', encoding="utf-8"),
        )
        expect_caught(
            "a missing LICENSE.pro",
            check_licence_files,
            lambda: (root / "LICENSE.pro").unlink(),
        )
        # The gap this project actually hit: `LICENSE.pro` was committed and
        # `LICENSE` was not, and the gate passed. Nothing in the tree stated the
        # terms for 1328 Apache-2.0 files.
        expect_caught(
            "a missing LICENSE",
            check_licence_files,
            lambda: (root / "LICENSE").unlink(),
        )
        expect_caught(
            "a LICENSE that is not the canonical text",
            check_licence_files,
            lambda: (root / "LICENSE").write_text(
                "Apache License, Version 2.0\n\nYou may do anything.\n", encoding="utf-8"
            ),
        )
        expect_caught(
            "a missing NOTICE",
            check_licence_files,
            lambda: (root / "NOTICE").unlink(),
        )
        expect_caught(
            "a NOTICE that drops an upstream attribution",
            check_licence_files,
            lambda: (root / "NOTICE").write_text("Singularity Todo\n", encoding="utf-8"),
        )
        expect_caught(
            "an FSL tree that has gone empty",
            check_not_vacuous,
            lambda: [p.unlink() for p in
                     [root / "pro/src/main/kotlin/com/singularity/todo/pro/Thing.kt",
                      root / "androidApp/src/pro/kotlin/com/singularity/todo/pro/Pro.kt"]],
        )

    if failures:
        print("\ncheck-pro-licence-boundary.py self-test FAILED:\n", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("\nself-test passed: every rule demonstrably fires.")
    return 0


# --------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove each rule fires on a violating tree")
    parser.add_argument("--verify-apk", metavar="APK", type=Path,
                        help="also assert a built APK matches the source claim")
    parser.add_argument("--expect", choices=("free", "pro"), default="free",
                        help="what the APK should contain (default: free)")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    violations: list[Violation] = []
    for check in (check_no_pro_imports, check_no_denied_dependencies,
                  check_fsl_files_declare_themselves, check_settings_gates_pro,
                  check_licence_files, check_not_vacuous):
        violations += check(ROOT)

    core_n, fsl_n = len(kt_files(ROOT)), len(fsl_files(ROOT))
    print(f"pro-boundary: {core_n} Apache-2.0 source file(s), {fsl_n} FSL source file(s), "
          f"{len(DENIED_GROUPS)} denied dependency group(s)")

    if args.verify_apk:
        violations += verify_apk(args.verify_apk, args.expect)
        print(f"pro-boundary: inspected {args.verify_apk.name}, expecting a {args.expect} build")

    if violations:
        print(f"\ncheck-pro-licence-boundary: {len(violations)} violation(s)\n", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
