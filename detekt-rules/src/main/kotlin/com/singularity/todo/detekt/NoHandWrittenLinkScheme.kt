package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * A link grammar lives in one home, and a string literal outside it is a second copy.
 *
 * ## Why
 *
 * The format of a deep link (`singularity://task/{id}`) is declared in
 * `TaskLinkSpec`; the format of an internal link (`task://`, `note://`) is declared in
 * `core/links/InternalLinks.kt`. Both were once written out by hand in three places,
 * and a change to one left the others in the old format: a link written by the old
 * producer stopped opening in the new matcher, and nothing failed.
 *
 * The compiler has no opinion about a string literal, so this rule is the opinion: a
 * literal that names a scheme outside its home is a copy of the grammar that will
 * drift.
 *
 * ## What is a home
 *
 * - a file whose name ends with `LinkSpec.kt`, or is `InternalLinks.kt`;
 * - a file that declares a `const val` whose name mentions `SCHEME`, `HOST`, `MARKER`,
 *   `PREFIX` or `URI` — the declaration is what makes a file the home, not its path,
 *   so a home that moves does not have to be listed again.
 *
 * ## Scope
 *
 * Production source sets only. The repository's tests spell the schemes on purpose —
 * that is how a parser is tested — and a rule that flagged them would be turned off
 * within a day. A file is a test when its own name ends with `Test.kt` / `Tests.kt`,
 * or when a directory in its path is a source set ending in `Test` (`commonTest`,
 * `jvmTest`, `androidHostTest`).
 *
 * The file name carries the check on its own because the harness that compiles a
 * fixture for a rule's unit test keeps the name and not the directories: a rule that
 * read only the path would flag its own fixture and pass the test for the wrong reason.
 *
 * ## Known limits
 *
 * - Detekt is per file, so the rule cannot say which home a scheme *should* have
 *   used; it says that the literal is not in one.
 * - A scheme assembled at runtime (`"task" + "://"`) is invisible to any rule that
 *   reads literals, this one included.
 * - Unlike the source-text scan this replaces, triple-quoted literals are found too:
 *   a multiline template is still a string template. That is stricter, and no
 *   production literal in the tree is one today.
 *
 * @see LinkGrammarRulesProvider for registration.
 */
class NoHandWrittenLinkSchemeRule(config: Config) : Rule(config, "", null) {
    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        if (!isProductionFile(root) || isHome(root)) return

        root.accept(
            object : KtTreeVisitorVoid() {
                override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
                    val literal =
                        expression.entries
                            .filterIsInstance<KtLiteralStringTemplateEntry>()
                            .joinToString("") { entry -> entry.text }
                    val scheme = SCHEMES.firstOrNull { it in literal }
                    if (scheme != null) {
                        report(
                            Finding(
                                entity = Entity.from(expression),
                                message =
                                    "the string literal \"$literal\" names the `$scheme` " +
                                        "scheme — the grammar has a home (TaskLinkSpec, " +
                                        "core/links/InternalLinks.kt), and a second copy of " +
                                        "it is a link that drifts",
                                references = emptyList(),
                                suppressReasons = emptyList(),
                            ),
                        )
                    }
                    super.visitStringTemplateExpression(expression)
                }
            },
        )
    }

    /** The file declares the grammar, or is one of the two files that own it. */
    private fun isHome(root: KtFile): Boolean {
        if (root.name.endsWith("LinkSpec.kt") || root.name == "InternalLinks.kt") return true
        var declaresAGrammar = false
        root.accept(
            object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    val name = property.name
                    if (property.hasModifier(KtTokens.CONST_KEYWORD) &&
                        name != null &&
                        HOME_NAME.containsMatchIn(name)
                    ) {
                        declaresAGrammar = true
                    }
                    super.visitProperty(property)
                }
            },
        )
        return declaresAGrammar
    }

    /** A production file: not a test by its own name, and not under a `*Test` source set. */
    private fun isProductionFile(root: KtFile): Boolean {
        val name = root.name
        if (name.endsWith("Test.kt") || name.endsWith("Tests.kt")) return false
        val path = root.virtualFile?.path?.replace('\\', '/') ?: return true
        return path.split('/').none { segment -> segment.endsWith("Test") }
    }

    private companion object {
        val SCHEMES = listOf("singularity://", "task://", "note://")

        /** The names that mark a file as the grammar's home. */
        val HOME_NAME = Regex("(SCHEME|HOST|MARKER|PREFIX|URI)")
    }
}

class LinkGrammarRulesProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("link-grammar")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf<RuleName, (Config) -> Rule>(
                RuleName("NoHandWrittenLinkScheme") to { cfg: Config -> NoHandWrittenLinkSchemeRule(cfg) },
            ),
        )
}
