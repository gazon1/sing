"""Tests for the generator of the :detekt-rules module config.

`scripts/gen-detekt-rules-config.py` copies `config/detekt/detekt.yml` with the custom
rule-set blocks removed, applying a small set of module-specific overrides. Its `--check`
mode compares the generated file with the generator's own output — so the generator and
its output can agree with each other and both disagree with `detekt.yml`.

That is what happened (#1971/#2042). `_drop_rule_block` skipped every line that began
with whitespace, not every line of the block it was replacing. Replacing `ReturnCount`
therefore ate the whole remainder of the `style:` section, and the twelve rules after it
— from `EqualsOnSignatureLine` to `LoopWithTooManyJumpStatements` — never reached the
module's own config. Nothing failed: `--check` reported the file current.

Two properties are held here:

* an override replaces its own rule and nothing else — the rules above and below it, and
  every later section, survive;
* the built-in rule names in the generated config equal those of the source it is
  generated from, section by section. This is the assertion the gate lacked, because the
  gate never compares the generated file with `detekt.yml` itself.
"""

import importlib.util
import pathlib
import unittest

import yaml

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
GENERATOR = SCRIPTS / "gen-detekt-rules-config.py"

#: One style section shaped like the real one: the rule the override replaces sits in the
#: middle, so a replacement that consumes every indented line loses the rules after it.
STYLE_SECTION = """\
style:
  active: true
  AboveTheOverride:
    active: true
  ReturnCount:
    active: true
    max: 4
  BelowTheOverride:
    active: true
    option: 3
  LastRule:
    active: false

complexity:
  active: true
  LongMethod:
    active: true
"""


def _load_generator():
    spec = importlib.util.spec_from_file_location("gen_detekt_rules_config", GENERATOR)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GEN = _load_generator()

# The generator's own paths, not a second derivation of them: a test that rebuilt the
# paths could agree with the tree while disagreeing with what the generator reads and
# writes, which is the class of silent drift this test exists to catch.
SOURCE = GEN.SRC
GENERATED = GEN.DST


def _rules(section: dict) -> set:
    return {name for name in section if name != "active"}


class OverrideReplacementTest(unittest.TestCase):
    """An override replaces one rule block, not the rest of its section."""

    def setUp(self) -> None:
        self.out = GEN.apply_overrides(STYLE_SECTION)

    def test_the_rules_below_the_override_survive(self) -> None:
        style = yaml.safe_load(self.out)["style"]
        self.assertEqual(
            {"AboveTheOverride", "BelowTheOverride", "LastRule", "ReturnCount"},
            _rules(style),
        )

    def test_the_override_is_applied(self) -> None:
        style = yaml.safe_load(self.out)["style"]
        self.assertEqual({"active": True, "max": 16}, style["ReturnCount"])

    def test_a_later_section_is_untouched(self) -> None:
        complexity = yaml.safe_load(self.out)["complexity"]
        self.assertEqual({"active": True, "LongMethod": {"active": True}}, complexity)

    def test_no_rule_block_is_duplicated(self) -> None:
        self.assertIsNone(GEN._first_duplicate_key(self.out))


class GeneratedConfigMirrorsTheSourceTest(unittest.TestCase):
    """Every built-in rule of detekt.yml reaches the generated module config."""

    def setUp(self) -> None:
        self.source = yaml.safe_load(SOURCE.read_text())
        self.generated = yaml.safe_load(GENERATED.read_text())

    def test_every_section_carries_the_source_rules(self) -> None:
        for section, rules in self.generated.items():
            if not isinstance(rules, dict):
                continue
            with self.subTest(section=section):
                self.assertEqual(_rules(self.source.get(section, {})), _rules(rules))

    def test_the_style_rules_below_the_override_are_present(self) -> None:
        # Named explicitly, because their absence was silent for as long as it lasted.
        style = _rules(self.generated["style"])
        for rule in (
            "EqualsOnSignatureLine",
            "ForbiddenComment",
            "MagicNumber",
            "MandatoryBracesLoops",
            "LoopWithTooManyJumpStatements",
        ):
            with self.subTest(rule=rule):
                self.assertIn(rule, style)

    def test_the_module_override_is_the_only_difference(self) -> None:
        # Everything else in `style:` is copied, values included — not just the rule
        # names. A second module-specific tuning would fail here, which is the point.
        source_style = dict(self.source["style"])
        generated_style = dict(self.generated["style"])
        source_style.pop("ReturnCount")
        generated_style.pop("ReturnCount")
        self.assertEqual(source_style, generated_style)
        self.assertEqual(
            {"active": True, "max": 16}, self.generated["style"]["ReturnCount"]
        )


if __name__ == "__main__":
    unittest.main()
