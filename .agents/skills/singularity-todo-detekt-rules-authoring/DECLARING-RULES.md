## Declaring a rule you did not write (`check-rule-intent.py`)

A rule absent from `detekt.yml` runs on detekt's **built-in default**, which is
invisible in review: not off, not misconfigured, just undecided. 18 such rules
held 247 of 428 baseline entries. `check-rule-intent.py` fails when a rule reports
findings without a declaration.

Declaring one is not as mechanical as it looks, because **the key spelling
depends on which ruleset the rule belongs to**, and getting it wrong fails
silently:

| Ruleset | Key validation | Correct spelling |
|---|---|---|
| `naming:`, `style:` | **strict** — an unknown key is a hard build failure listing the allowed properties | the detekt property name, e.g. `ConstructorParameterNaming` |

The key spelling depends on which ruleset the rule belongs to, and getting it
wrong fails silently: the file already contained both `ImportOrdering` and
`FunctionSignature` in camelCase next to `no-wildcard-imports` and
`parameter-list-wrapping` in kebab; the kebab ones were doing nothing.

**There is no `ktlint:` ruleset any more.** The ktlint wrapper was removed from
detekt (ADR `2026-11-02-ktlint-cli-replaces-the-detekt-formatting-layer.md`):
formatting rules live in the standalone ktlint CLI and are configured in
`.editorconfig` under `ktlint_*` keys. A detekt rule that was only a formatting
wrapper has no home in detekt's config — it is either deleted or rewritten as a
semantic rule.

**How to find out whether a key is a detekt property at all, without guessing:**
declare it wrong under `style:` on purpose. detekt replies `Property 'style>Foo'
is misspelled or does not exist. Did you mean '…'? Allowed properties: [...]` —
that single message names the rule set that owns it.

**A stale report is not evidence.** `detektBaseline` combined with `:detekt` in
one invocation can leave the report from a previous run on disk. A
`:desktopApp:detekt` that printed 5 `Indentation` findings after the file was
already fixed was a stale report; `--rerun-tasks --no-build-cache` showed 0.

6. **Assert the exact count, not just "> 0".** `NoRealDelayInTest` reported
   `Thread.sleep` from both `visitCallExpression` and `visitDotQualifiedExpression`,
   double-counting every occurrence. A test asserting `assertTrue(findings.isNotEmpty())`
   would have passed. Use `assertEquals(1, findings.size)`.

7. **An inactive rule is not a broken rule.** `NoRunCatchingInSuspend` was
   `active: false` on purpose, pending a 239-site migration. Before "fixing" a rule
   that reports nothing, check `active:` in `config/detekt/detekt.yml` and read the
   KDoc — an audit once listed it as vacuous when it was merely switched off.

**Never use inline Kotlin-compiler PSI helpers in a detekt plugin.**
`psiUtil.collectDescendantsOfType` and friends are `inline`, so the synthetic
`$inlined$…` class fails to load inside detekt's classloader and throws
`NoClassDefFoundError`. Because the exception escapes the rule, `:shared:detekt` fails
**and writes no report**, leaving the previous run's file on disk — which reads as a
clean pass. Walk `node.children` explicitly instead; see
`2026-09-28-detekt-daemon-and-crashing-rule`.

**A failed build task's report is not evidence.** When a rule throws, or the task fails
for any other reason, the report on disk is the previous run's. Check the task outcome
before reading it.
