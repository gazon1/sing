package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope

/**
 * One Konsist scope over `commonMain`, built once per JVM.
 *
 * Ten call sites used to call `Konsist.scopeFromExternalDirectory(commonMainRoot)`
 * each, re-parsing all 1,238 files ten times over. The tree cannot change while
 * the test run is going, so a single lazy scope is enough.
 */
internal object CommonMainKonsistScope {

    val commonMainRoot: String = System.getProperty("commonMain.root")
        ?: error("commonMain.root system property is not set — see the jvmTest task config")

    val scope: KoScope by lazy { Konsist.scopeFromExternalDirectory(commonMainRoot) }
}
