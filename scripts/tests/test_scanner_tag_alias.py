"""The Python half of the "an aliased `@Tag` is still a tag" contract.

Run with: python3 -m unittest discover -s scripts/tests

`TestTagCoverageTest` (Kotlin, shared/src/jvmTest) resolves an aliased
`import org.junit.jupiter.api.Tag as X` from the import itself, and fails any
class that carries a tag it could not read. `scripts/src/scanners/
test_classes.py` answers the same question for the Python gates — and it
answered it differently until this suite existed: it read `@JUnitTag("slow")`
as *untagged*, which put two real classes in desktopApp into the population
that no `-Ptest.tags` selection reaches.

The two answers must be the same answer. The alias is not a stylistic choice:
a file that imports the domain entity `com.singularity.todo.feature.tags.Tag`,
or `kotlin.test.Test`, has to rename the annotation — so the alias is
mandatory in exactly the files where the plain reading breaks.

The contract is checked in three parts:

1. The Kotlin regex and the Python regex find the SAME aliased files. The
   Kotlin pattern is read out of `TestTagCoverageTest.kt` rather than
   re-typed here, so a change on the Kotlin side fails this suite instead of
   quietly creating a second answer.
2. No class in an aliased file is read as untagged.
3. A synthetic source exercises the resolution directly — so the guarantee
   does not rest on which files happen to exist today.
"""

import pathlib
import re
import sys
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(_ROOT / "scripts"))

import scanning  # noqa: E402 — needs the sys.path entry above

KOTLIN_GATE = (
    _ROOT / "shared/src/jvmTest/kotlin/com/singularity/todo/arch/TestTagCoverageTest.kt"
)

#: The Kotlin source sets the tag filter applies to. A file in one of them
#: MUST carry a readable tag, or `includeTags` selects nothing for it.
TAG_FILTERED_ROOTS = (
    _ROOT / "shared/src/commonTest/kotlin",
    _ROOT / "shared/src/jvmTest/kotlin",
    _ROOT / "desktopApp/src/jvmTest/kotlin",
    _ROOT / "mcp-server/src/test/kotlin",
)


def kotlin_alias_regex() -> re.Pattern[str]:
    """The alias pattern as the Kotlin gate spells it.

    Read out of `TestTagCoverageTest.kt` instead of being re-typed: a copy
    would be a second answer to the same question, which is the failure this
    suite exists to prevent. The Kotlin raw string needs no unescaping —
    every metacharacter in it is a Python metacharacter too.
    """
    source = KOTLIN_GATE.read_text(encoding="utf-8")
    marker = "TAG_ALIAS_IMPORT = Regex("
    start = source.index(marker)
    quote = '"' * 3
    literal_start = source.index(quote, start) + 3
    literal_end = source.index(quote, literal_start)
    return re.compile(source[literal_start:literal_end])


def kotlin_aliased_files() -> dict[pathlib.Path, str]:
    """Every aliased file, and the alias it uses, as the Kotlin gate sees it."""
    pattern = kotlin_alias_regex()
    found: dict[pathlib.Path, str] = {}
    for root in TAG_FILTERED_ROOTS:
        if not root.is_dir():
            continue
        for kt in root.rglob("*.kt"):
            for line in kt.read_text(encoding="utf-8", errors="replace").splitlines():
                matched = pattern.match(line)
                if matched:
                    found[kt] = matched.group(1)
                    break
    return found


class TagAliasResolutionTest(unittest.TestCase):
    def test_kotlin_and_python_find_the_same_aliased_files(self) -> None:
        """The two implementations must not disagree about WHO aliases.

        A file aliased on the Kotlin side and not on the Python side is a
        class the Python gate reads as untagged — exactly the defect this
        suite was written for.
        """
        pattern = kotlin_alias_regex()
        python_aliased: dict[pathlib.Path, str] = {}
        for root in TAG_FILTERED_ROOTS:
            if not root.is_dir():
                continue
            for kt in root.rglob("*.kt"):
                source = kt.read_text(encoding="utf-8", errors="replace")
                for line in source.splitlines():
                    matched = pattern.match(line)
                    if matched:
                        python_aliased[kt] = matched.group(1)
                        break

        kotlin_aliased = kotlin_aliased_files()
        self.assertEqual(
            sorted(str(p) for p in kotlin_aliased),
            sorted(str(p) for p in python_aliased),
            "the Kotlin and Python alias patterns disagree about which files alias @Tag",
        )

    def test_no_class_in_an_aliased_file_reads_as_untagged(self) -> None:
        """Every class in an aliased file must read with the tag it declares.

        The tag is read by the scanner; the expectation is taken from the
        annotation the file actually writes, so this cannot pass by both
        sides agreeing on "untagged".
        """
        aliased = kotlin_aliased_files()
        self.assertTrue(
            aliased,
            "no aliased @Tag import found in the tag-filtered source sets — "
            "the repository lost the case this suite covers, so the suite is "
            "asserting nothing",
        )
        for path, alias in sorted(aliased.items()):
            source = path.read_text(encoding="utf-8", errors="replace")
            declared = re.findall(rf'@{re.escape(alias)}\(\s*"([^"]+)"\s*\)', source)
            self.assertTrue(
                declared,
                f"{path} imports @Tag as {alias} but declares no tag with it",
            )
            for name in scanning.test_classes_in(source):
                tag = scanning.read_tag(source, name)
                self.assertNotEqual(
                    tag,
                    "untagged",
                    f"{path}:{name} is read as untagged, but the file writes "
                    f'@{alias}("{declared[0]}")',
                )
                self.assertIn(tag, declared)

    def test_synthetic_aliased_source_resolves(self) -> None:
        """The resolution itself, independent of which files exist today."""
        source = (
            "import org.junit.jupiter.api.Tag as JUnitTag\n"
            "import kotlin.test.Test\n"
            "\n"
            '@JUnitTag("slow")\n'
            "class AliasedTest {\n"
            "    @Test\n"
            "    fun runs() = Unit\n"
            "}\n"
        )
        self.assertEqual(scanning.read_tag(source, "AliasedTest"), "slow")

    def test_plain_source_still_resolves(self) -> None:
        """The plain spelling is unaffected by the alias branch."""
        source = (
            "import org.junit.jupiter.api.Tag\n"
            "\n"
            '@Tag("fast")\n'
            "class PlainTest {\n"
            "    @Test\n"
            "    fun runs() = Unit\n"
            "}\n"
        )
        self.assertEqual(scanning.read_tag(source, "PlainTest"), "fast")

    def test_fully_qualified_source_still_resolves(self) -> None:
        """The fully-qualified spelling is unaffected by the alias branch."""
        source = (
            "import com.singularity.todo.feature.tags.Tag\n"
            "\n"
            '@org.junit.jupiter.api.Tag("fast")\n'
            "class QualifiedTest {\n"
            "    @Test\n"
            "    fun runs() = Unit\n"
            "}\n"
        )
        self.assertEqual(scanning.read_tag(source, "QualifiedTest"), "fast")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
