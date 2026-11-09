package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoBaseDeclaration
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoInterfaceDeclaration
import com.lemonappdev.konsist.api.declaration.KoObjectDeclaration
import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration
import com.lemonappdev.konsist.api.provider.KoDeclarationProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A screen state holds the persistent kind, and the register of its debt is the truth.
 *
 * ## The defect
 *
 * A screen state is what a screen reads. When it holds a raw `List` / `Set` / `Map`, the
 * state cannot promise that the instance it holds will not change, and the collection is
 * rebuilt on the wrong side of the boundary. The state that owns the value must hold
 * `ImmutableList` / `ImmutableSet` / `ImmutableMap`, and the screen receives that
 * instance unchanged.
 *
 * The sibling rule — `NoUnstableCollectionParam` — asks what a composable takes as a
 * *parameter*. It cannot see inside the state: a screen that receives `ArchiveUiState`
 * passes that rule while the state carries `List<Task>`.
 *
 * ## Why a Konsist test and not a Detekt rule
 *
 * The gate has two directions, and a per-file rule can only hold one:
 *
 * 1. a raw collection a state holds and the register does not name — a new debt;
 * 2. a register entry the code no longer has — the debt was paid, and the record has to
 *    move with it.
 *
 * The second direction needs the whole tree: a rule that sees one file cannot know that
 * a property is gone from another. Konsist has the whole tree, so both live here.
 *
 * ## The register is the file, not a copy
 *
 * `config/compose/screen_state_collection_debt.conf` is the register. This test reads it
 * as it is; it holds no second copy of the entries. The file also records
 * `properties = N`, and the number is the ratchet: the debt may not grow without
 * somebody raising it, and a shrink has to be recorded in the same commit.
 *
 * The states of the core (`SyncState`, `EphemeralState`) are out of scope: they are the
 * state of a repository and of a settings bundle, not of a screen. The scope is
 * `com.singularity.todo.feature.`.
 */
@Tag("fast")
class ScreenStateCollectionsTest {
    @Test
    fun `a raw collection a screen state holds is in the register`() {
        val registered = register
        val findings =
            holdings().mapNotNull { (key, type) ->
                when (registered[key]) {
                    null ->
                        "  - $key : $type — a screen state holds a raw collection; the " +
                            "state that owns the value must hold the persistent kind " +
                            "(ImmutableList / ImmutableSet / ImmutableMap)"
                    type ->
                        null
                    else ->
                        "  - $key : $type — the register says ${registered[key]}; the " +
                            "declared type and the record disagree"
                }
            }

        assertTrue(
            findings.isEmpty(),
            "A screen state holds a raw collection that the register does not admit:\n" +
                findings.joinToString("\n") + "\n\n" +
                "Hold the persistent kind, or add the entry to " +
                "config/compose/screen_state_collection_debt.conf and raise `properties`.",
        )
    }

    @Test
    fun `the register names only debts the code still has`() {
        val held = holdings().keys
        val stale =
            register.keys
                .filterNot { key -> key in held }
                .sorted()
                .map { key ->
                    "  - $key : ${register[key]} — the register names a debt the code no " +
                        "longer has; the debt was paid and the record has to move with it"
                }

        assertTrue(
            stale.isEmpty(),
            "The register is stale:\n" + stale.joinToString("\n") + "\n\n" +
                "Drop the entry from config/compose/screen_state_collection_debt.conf " +
                "and lower `properties` in the same commit.",
        )
    }

    @Test
    fun `the register records its own size`() {
        val recorded = recordedSize
        assertTrue(
            recorded != null,
            "config/compose/screen_state_collection_debt.conf records no `properties = N`; " +
                "the debt may not grow without somebody deciding to raise the bar.",
        )
        assertTrue(
            recorded == register.size,
            "The register records $recorded propertie(s) and holds ${register.size}; " +
                "the recorded size is the ratchet and it has to match the register.",
        )
    }

    @Test
    fun `the scan finds the states it is meant to judge`() {
        assertTrue(
            states().isNotEmpty(),
            "The scan found zero screen states — a gate that reads nothing measures " +
                "nothing. Check that the source roots are configured.",
        )
    }

    // ── the scan ────────────────────────────────────────────────────────────────

    private data class Held(val name: String, val type: String)

    private data class State(val qualifiedName: String, val properties: List<Held>)

    /** Every declaration the five production roots hold, with its package. */
    private fun declarations(): List<Pair<String, State>> {
        val found = mutableListOf<Pair<String, State>>()
        for (root in roots) {
            require(root.isDirectory) { "missing source root: ${root.path}" }
            val scope = Konsist.scopeFromExternalDirectory(root.path)
            for (file in scope.files) {
                val packageName = file.packagee?.name ?: continue
                collect(file.declarations(), packageName, "", found)
            }
        }
        return found
    }

    private fun collect(
        declarations: List<KoBaseDeclaration>,
        packageName: String,
        prefix: String,
        out: MutableList<Pair<String, State>>,
    ) {
        for (declaration in declarations) {
            if (declaration !is KoClassDeclaration &&
                declaration !is KoInterfaceDeclaration &&
                declaration !is KoObjectDeclaration
            ) {
                continue
            }
            val name = (declaration as KoNameProvider).name
            val qualified = if (prefix.isEmpty()) "$packageName.$name" else "$prefix.$name"
            out += packageName to State(qualified, propertiesOf(declaration))
            collect(
                (declaration as KoDeclarationProvider).declarations(includeNested = false),
                packageName,
                qualified,
                out,
            )
        }
    }

    /** The constructor properties and the body properties, by declared type. */
    private fun propertiesOf(declaration: KoBaseDeclaration): List<Held> {
        val held = mutableListOf<Held>()
        val constructor = (declaration as? KoClassDeclaration)?.primaryConstructor
        constructor
            ?.parameters
            ?.filter { parameter -> parameter.isVal || parameter.isVar }
            ?.forEach { parameter ->
                val type = parameter.type?.sourceType ?: return@forEach
                held += Held(parameter.name, type)
            }
        (declaration as KoDeclarationProvider)
            .declarations(includeNested = false)
            .filterIsInstance<KoPropertyDeclaration>()
            // A computed property (`val allEntries get() = …`) reads a collection the
            // state already holds; it does not hold a second one. The gate asks what
            // the state *holds*, which is what the register records.
            .filter { property -> !property.hasGetter }
            .forEach { property ->
                val type = property.type?.sourceType ?: return@forEach
                held += Held(property.name, type)
            }
        return held
    }

    /** The screen states of a feature. */
    private fun states(): List<State> =
        declarations()
            .filter { (packageName, _) -> packageName.startsWith(FEATURE_PACKAGE) }
            .map { (_, state) -> state }
            .filter { state -> isState(state.qualifiedName) }

    private fun isState(qualifiedName: String): Boolean {
        val simple = qualifiedName.substringAfterLast('.')
        if (STATE_SUFFIXES.any { suffix -> simple.endsWith(suffix) }) return true
        val parent = qualifiedName.substringBeforeLast('.', "")
        return parent in stateNames
    }

    private val stateNames: Set<String> by lazy {
        declarations()
            .map { (_, state) -> state.qualifiedName }
            .filter { qualified ->
                STATE_SUFFIXES.any { suffix -> qualified.substringAfterLast('.').endsWith(suffix) }
            }
            .toSet()
    }

    /** The raw collections their states hold, keyed by `<qualified name>.<property>`. */
    private fun holdings(): Map<String, String> =
        states()
            .flatMap { state ->
                state.properties
                    .filter { held -> isRawCollection(held.type) }
                    .map { held -> "${state.qualifiedName}.${held.name}" to held.type }
            }
            .toMap()

    private fun isRawCollection(type: String): Boolean {
        val bare =
            type.trim()
                .removeSuffix("?")
                .substringBefore('<')
                .trim()
                .substringAfterLast('.')
        return bare in RAW_COLLECTIONS
    }

    // ── the register, as the file writes it ─────────────────────────────────────

    private val register: Map<String, String> by lazy {
        entryLines().associate { line ->
            val key = line.substringBefore(':').trim()
            val declared = line.substringAfter(':').trim()
            key to declared
        }
    }

    private val recordedSize: Int? by lazy {
        rawLines()
            .firstOrNull { line -> line.startsWith("properties") }
            ?.substringAfter('=')
            ?.trim()
            ?.toIntOrNull()
    }

    private fun rawLines(): List<String> =
        registerFile.readLines()
            .map { line -> line.substringBefore('#').trim() }
            .filter { line -> line.isNotEmpty() }

    private fun entryLines(): List<String> =
        rawLines().filter { line -> !line.startsWith("properties") }

    private companion object {
        /** The package prefix of a feature; the core's states are not screens. */
        const val FEATURE_PACKAGE = "com.singularity.todo.feature."

        val STATE_SUFFIXES = listOf("UiState", "State")

        /** The same list `:detekt-rules` uses — two checks, one answer. */
        val RAW_COLLECTIONS =
            setOf(
                "List",
                "MutableList",
                "ArrayList",
                "Set",
                "MutableSet",
                "HashSet",
                "Map",
                "MutableMap",
                "HashMap",
                "Collection",
                "Array",
            )

        /** `commonMain.root` is `shared/src/commonMain/kotlin`; four levels up is the repo. */
        val repoRoot: File =
            File(
                System.getProperty("commonMain.root")
                    ?: error("commonMain.root is not set — see the jvmTest task config"),
            ).parentFile.parentFile.parentFile.parentFile

        val roots: List<File> =
            listOf(
                "shared/src/commonMain/kotlin",
                "shared/src/androidMain/kotlin",
                "shared/src/jvmMain/kotlin",
                "androidApp/src/main/kotlin",
                "desktopApp/src/main/kotlin",
            ).map { path -> File(repoRoot, path) }

        val registerFile: File =
            File(repoRoot, "config/compose/screen_state_collection_debt.conf")
    }
}
