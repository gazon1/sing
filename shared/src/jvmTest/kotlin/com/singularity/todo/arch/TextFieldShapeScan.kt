package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration

/**
 * The shape of a text field, derived from the code that owns it.
 *
 * ## What a shape is
 *
 * A state-based field has to answer one question: **who owns the text?** The app has
 * three answers, and each has its own helper:
 *
 * | Shape | Who owns the text | Helper |
 * |---|---|---|
 * | **local** | the field itself | `rememberTextFieldState` |
 * | **bridged** | the field *and* a ViewModel-owned string, in sync | `rememberBridgedTextFieldState` |
 * | **anchored** | an outside value; the field only shows it | `rememberAnchoredTextFieldState` |
 *
 * ## Why the shape is read from the imports
 *
 * The shape used to be read out of the file's *text* — the scan looked for the
 * helper's name in the source. That is the same trick the rewrite contract got
 * rid of: a text scan cannot tell a call from a mention, so a KDoc line that
 * names the helper was enough to classify a file that owns no field at all, and a
 * helper named in a comment could hide a field that does.
 *
 * The imports are a different kind of evidence. A file that calls
 * `rememberBridgedTextFieldState` has to *import* it: the helper lives in
 * `com.singularity.todo.core.ui.textfields`, and a file in any other package
 * cannot reach it without an import. A mention in a comment imports nothing.
 *
 * The import is therefore not a hint — it is the compiler's own record that the
 * symbol is used.
 *
 * ## Why the helpers live in their own package
 *
 * The helpers used to live in `core.ui.components`, next to the fields that use
 * them. A file in that package could call `rememberBridgedTextFieldState` without
 * an import — same package — and the import-based derivation would have missed
 * it. Moving the helpers to `core.ui.textfields` makes the import mandatory
 * everywhere, which is what lets the derivation be exact.
 *
 * The package holds no field sites, so the helpers are excluded from the scan by
 * their path rather than by a list of file names that would rot.
 */
internal enum class FieldShape(val label: String) {
    LOCAL("local"),
    BRIDGED("bridged"),
    ANCHORED("anchored"),
}

/**
 * Whether the function is a preview — a fixture that stands in for an owner.
 *
 * A preview may build a source: it is not the owner, it is a picture of one.
 */
internal fun KoFunctionDeclaration.isPreview(): Boolean =
    annotations.any { it.name == "Preview" }

/**
 * Whether the property declares a source of [kind].
 *
 * Konsist reports the *first* type reference of a property, and for an extension
 * property that is the receiver's type: `val NotesUiState.searchQuerySource:
 * StaticText` is a property whose first type is `NotesUiState`. So the declared
 * type is the answer when the property has no receiver, and the declaration's own
 * text — comments stripped — when it has one.
 *
 * The type is read as its *bare* name: a property of a generic type declares
 * `Revisioned<String>`, and what matters is the `Revisioned` — the type arguments say
 * what the value is, not what kind of thing it is.
 */
internal fun KoPropertyDeclaration.declaresSourceOf(kind: String): Boolean {
    if (receiverType == null) return type?.bareSourceType == kind
    val code = KotlinText.stripComments(text)
    return Regex("""\b$kind\b""").containsMatchIn(code)
}

/**
 * Whether the function hands out a source of [kind].
 *
 * An owner that builds a source does not always keep it in a property: the GenUI
 * data context builds the source of a bound field in a function and hands it out
 * whole. The return type is the evidence — the function's *name* says nothing, and
 * the body of it may say anything.
 */
internal fun KoFunctionDeclaration.returnsSourceOf(kind: String): Boolean {
    val type = returnType ?: return false
    if (type.name == kind) return true
    return type.typeArguments.orEmpty().any { argument -> argument.name == kind }
}

/**
 * The scan itself: the shape of every file that owns a text field, and the
 * declarations of any file in `commonMain`.
 */
internal object TextFieldShapeScan {

    /**
     * Root of `commonMain`'s sources, as set by the `jvmTest` task.
     *
     * A property rather than a relative path: the test JVM's working directory is
     * a build output directory (the Allure listener writes there), so a relative
     * path would resolve against the wrong tree.
     */
    private val commonMainRoot: String = System.getProperty("commonMain.root")
        ?: error("commonMain.root system property is not set — see the jvmTest task config")

    /**
     * The whole of `commonMain`, read by Konsist.
     *
     * Absolute path — hence `scopeFromExternalDirectory` (`scopeFromDirectory`
     * resolves against Konsist's detected project root and would double the
     * prefix).
     */
    private val scope = Konsist.scopeFromExternalDirectory(commonMainRoot)

    /** Every file of `commonMain`, keyed by its path relative to `com/singularity/todo/`. */
    private val files: Map<String, KoFileDeclaration> by lazy {
        scope.files.associateBy { relativePath(it) }
    }

    /**
     * The shape of every file that owns a text field.
     *
     * The keys are paths relative to `com/singularity/todo/`, the same shape the
     * registries use. The helpers' own package is left out: it holds the
     * helpers, not fields.
     */
    fun shapes(): Map<String, Set<FieldShape>> =
        files.entries.mapNotNull { (path, file) ->
            if (path.startsWith(HELPERS_PACKAGE)) return@mapNotNull null
            val imports = file.imports.map { it.name }
            val shapes = buildSet {
                if (LOCAL_HELPER in imports) add(FieldShape.LOCAL)
                if (BRIDGED_HELPER in imports) add(FieldShape.BRIDGED)
                if (ANCHORED_HELPER in imports) add(FieldShape.ANCHORED)
            }
            if (shapes.isEmpty()) null else path to shapes
        }.toMap()

    /**
     * The file at [path], relative to `com/singularity/todo/`.
     *
     * Fails the test when the file does not exist: a registry that names a file
     * that is gone is a registry that is wrong, and the failure has to be loud.
     */
    fun file(path: String): KoFileDeclaration =
        files[path] ?: error("no such file in commonMain: $path")

    /** The properties of the file at [path], nested ones included. */
    fun properties(path: String): List<KoPropertyDeclaration> = file(path).properties()

    /** The functions of the file at [path], nested ones included. */
    fun functions(path: String): List<KoFunctionDeclaration> = file(path).functions()

    /**
     * Every function that could name [fullyQualifiedName] in a signature.
     *
     * A type that travels in a signature has to be imported — unless the file sits in
     * the type's own package. So the whole tree is not the search space: the files
     * that import the type, and the files of its package, are the only ones that can
     * mention it.
     *
     * Walking every function of every file of `commonMain` cost fifteen minutes of a
     * twenty-five-minute suite — the single most expensive test in the repository. The
     * narrowed walk asks the same question of the same code and costs seconds.
     *
     * The type has to be where it is said to be: if it moved, the search would find
     * nothing and the rule would pass by looking at nothing. So the declaring file is
     * checked, and a missing one fails loudly instead of quietly.
     */
    fun functionsThatCanMention(fullyQualifiedName: String): List<KoFunctionDeclaration> {
        val typeName = fullyQualifiedName.substringAfterLast('.')
        val packageName = fullyQualifiedName.substringBeforeLast('.')
        val candidates = files.filter { (path, file) ->
            packageOf(path) == packageName ||
                file.imports.any { it.name.substringAfterLast('.') == typeName }
        }
        val declaresTheType = candidates.any { (path, file) ->
            packageOf(path) == packageName &&
                (
                    file.interfaces().any { it.name == typeName } ||
                        file.classes().any { it.name == typeName } ||
                        file.objects().any { it.name == typeName }
                )
        }
        check(declaresTheType) {
            "no file of $packageName declares $typeName — the rule would look at nothing"
        }
        return candidates.values.flatMap { it.functions() }
    }

    /**
     * The package a file lives in — derived from its path, the way the tree
     * mirrors the packages.
     *
     * The paths are relative to `com/singularity/todo/`, so the root package
     * has to be put back: `core/ui/Thing.kt` is in
     * `com.singularity.todo.core.ui`, and a package without its root names a
     * package that does not exist.
     */
    private fun packageOf(path: String): String =
        "$ROOT_PACKAGE.${path.substringBeforeLast('/').replace('/', '.')}"

    /**
     * The text of every declaration of the file at [path] — the functions and the
     * properties, nested ones included.
     *
     * Scoped to declarations on purpose: a file's own text includes its header
     * comment, and a header that mentions a counter is not a counter that moves.
     * The text is still the declaration's own text, so a comment *inside* a
     * declaration is still part of it — [KotlinText.stripComments] is what removes
     * that.
     */
    fun declarationTexts(path: String): List<String> {
        val file = file(path)
        return file.functions().map { it.text } + file.properties().map { it.text }
    }

    /** The imports of the file at [path]. */
    fun imports(path: String): List<String> = file(path).imports.map { it.name }

    /**
     * The text of every file of `commonMain`, with the path it belongs to.
     *
     * For the rules that read a file's whole text rather than a declaration's:
     * a rule about a call site, where the call may sit in an initializer and
     * not in a function at all.
     */
    fun allFileTexts(): List<Pair<String, String>> =
        files.entries.map { (path, file) -> path to file.text }

    /**
     * The path of a file, relative to `com/singularity/todo/`.
     *
     * The tree mirrors the packages, so the path *is* the name — no resolution
     * needed, and no need to believe Konsist's package resolution.
     */
    private fun relativePath(file: KoFileDeclaration): String {
        val normalizedRoot = commonMainRoot.replace('\\', '/').trimEnd('/')
        val under = file.path.replace('\\', '/').removePrefix("$normalizedRoot/")
        return under.removePrefix("$PACKAGE_PREFIX/")
    }

    /** The package every path in this scan is relative to. */
    private const val PACKAGE_PREFIX = "com/singularity/todo"

    /** The same package, in the form a `package` declaration uses. */
    private const val ROOT_PACKAGE = "com.singularity.todo"

    /** Where the helpers live. No field site lives here. */
    private const val HELPERS_PACKAGE = "core/ui/textfields/"

    /** Compose's own state holder: the field owns its text. */
    private const val LOCAL_HELPER = "androidx.compose.foundation.text.input.rememberTextFieldState"

    /** The bridge: the field and a ViewModel-owned string, in sync. */
    private const val BRIDGED_HELPER = "com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState"

    /** The anchor: an outside value, shown but not owned. */
    private const val ANCHORED_HELPER = "com.singularity.todo.core.ui.textfields.rememberAnchoredTextFieldState"
}
