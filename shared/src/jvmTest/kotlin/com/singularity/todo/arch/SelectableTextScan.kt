package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/**
 * What a file does with its text, derived from the code.
 *
 * ## The decision a text surface makes
 *
 * Every piece of text a person reads is one of three things:
 *
 * | Treatment | What the person gets | Widget |
 * |---|---|---|
 * | **selectable** | the text can be selected and copied | `SelectableText` |
 * | **linkified** | the text's links can be tapped, and it can be copied | `LinkifiedText` |
 * | **excluded** | neither — and the reason is written down | — |
 *
 * The third one is not a shrug. A row whose long press already opens a menu cannot
 * also start a text selection: the gesture is spoken for. The exclusion is a
 * decision somebody has to make on purpose, and it is recorded with its reason.
 *
 * ## Why the treatment is read from the imports
 *
 * The same argument the text-field convention makes: a file that calls
 * `SelectableText` has to *import* it — the widget lives in
 * `com.singularity.todo.core.ui.text`, and a file in another package cannot reach
 * it without an import. A mention in a comment imports nothing.
 *
 * The widgets' own package is left out of the scan: it holds the widgets, not the
 * surfaces that use them.
 */
internal enum class TextTreatment(val label: String) {
    SELECTABLE("selectable"),
    LINKIFIED("linkified"),
    EXCLUDED("excluded"),
}

/**
 * The scan itself: which files of `commonMain` use the text widgets, and how.
 */
internal object SelectableTextScan {

    /**
     * Root of `commonMain`'s sources, as set by the `jvmTest` task.
     *
     * A property rather than a relative path: the test JVM's working directory is a
     * build output directory, so a relative path would resolve against the wrong tree.
     */
    private val commonMainRoot: String = System.getProperty("commonMain.root")
        ?: error("commonMain.root system property is not set — see the jvmTest task config")

    private val scope = Konsist.scopeFromExternalDirectory(commonMainRoot)

    /** Every file of `commonMain`, keyed by its path relative to `com/singularity/todo/`. */
    private val files: Map<String, KoFileDeclaration> by lazy {
        scope.files.associateBy { relativePath(it) }
    }

    /** The path of every file of `commonMain`, relative to `com/singularity/todo/`. */
    fun files(): Set<String> = files.keys

    /**
     * The treatment of every file that uses a text widget.
     *
     * The keys are paths relative to `com/singularity/todo/`, the same shape the
     * registry uses.
     */
    fun treatments(): Map<String, Set<TextTreatment>> =
        files.entries.mapNotNull { (path, file) ->
            if (path.startsWith(WIDGETS_PACKAGE)) return@mapNotNull null
            val imports = file.imports.map { it.name }
            val treatments = buildSet {
                if (imports.any { it in SELECTABLE_WIDGETS }) add(TextTreatment.SELECTABLE)
                if (imports.any { it in LINKIFIED_WIDGETS }) add(TextTreatment.LINKIFIED)
            }
            if (treatments.isEmpty()) null else path to treatments
        }.toMap()

    /** The imports of the file at [path]. */
    fun imports(path: String): List<String> =
        files[path]?.imports?.map { it.name }
            ?: error("no such file in commonMain: $path")

    /**
     * The path of a file, relative to `com/singularity/todo/`.
     *
     * The tree mirrors the packages, so the path *is* the name.
     */
    private fun relativePath(file: KoFileDeclaration): String {
        val normalizedRoot = commonMainRoot.replace('\\', '/').trimEnd('/')
        val under = file.path.replace('\\', '/').removePrefix("$normalizedRoot/")
        return under.removePrefix("$PACKAGE_PREFIX/")
    }

    /** The package every path in this scan is relative to. */
    private const val PACKAGE_PREFIX = "com/singularity/todo"

    /** Where the widgets live. No text surface lives here. */
    private const val WIDGETS_PACKAGE = "core/ui/text/"

    /** The widgets that make text selectable. */
    private val SELECTABLE_WIDGETS = setOf(
        "com.singularity.todo.core.ui.text.SelectableText",
        "com.singularity.todo.core.ui.text.SelectableTextGroup",
        "com.singularity.todo.core.ui.text.UnselectableContent",
        "androidx.compose.foundation.text.selection.SelectionContainer",
        "androidx.compose.foundation.text.selection.DisableSelection",
    )

    /** The widgets that make text carry tappable links. */
    private val LINKIFIED_WIDGETS = setOf(
        "com.singularity.todo.core.ui.text.LinkifiedText",
    )
}
