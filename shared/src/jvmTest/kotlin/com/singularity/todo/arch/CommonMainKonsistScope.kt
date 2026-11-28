package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope

/**
 * One Konsist scope over every production module, built once per JVM.
 *
 * Ten call sites used to call `Konsist.scopeFromExternalDirectory(commonMainRoot)`
 * each, re-parsing all 1,238 files ten times over. The tree cannot change while
 * the test run is going, so a single lazy scope is enough.
 *
 * The scope spans `:shared`, `:core-domain`, `:core-database` and `:mvi`: each moved out
 * of `:shared` in turn, and a gate that kept one root would stop reading the moved code
 * without failing — a scan of less code reports green exactly like a scan of all of it.
 * The roots come from the build properties the `jvmTest` task sets, so a module whose
 * source set stops being configured is missing from the scope rather than silently
 * narrower.
 */
internal object CommonMainKonsistScope {
    val roots: List<String> =
        listOfNotNull(
            System.getProperty("commonMain.root"),
            System.getProperty("coreDomain.root"),
            System.getProperty("coreDatabase.root"),
            System.getProperty("mvi.root"),
        ).distinct()

    init {
        check(roots.isNotEmpty()) {
            "no production source root is configured — see the jvmTest task config"
        }
    }

    val scope: KoScope by lazy {
        roots.map { root -> Konsist.scopeFromExternalDirectory(root) }.reduce { all, next -> all + next }
    }

    /** Path under the source root, e.g. `com/singularity/todo/feature/notes/Ids.kt`. */
    fun sourcePath(path: String): String = path.replace('\\', '/').substringAfter("/kotlin/")

    /** Path under the `com/singularity/todo` package root, e.g. `feature/notes/Ids.kt`. */
    fun packagePath(path: String): String = sourcePath(path).removePrefix("com/singularity/todo/")
}
