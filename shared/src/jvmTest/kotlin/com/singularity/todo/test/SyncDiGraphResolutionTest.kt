package com.singularity.todo.test

import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.sync.SyncBootstrapper
import com.singularity.todo.core.sync.SyncDocumentWriter
import com.singularity.todo.core.sync.SyncEngine
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.gate.gateModule
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertNotNull

/**
 * The sync half of the desktop Koin graph resolves for real.
 *
 * ## Why this exists, and why it is narrow
 *
 * `dc7f1d5d` deleted `KoinGraphValidationTest` and replaced it with
 * `GenuiDiGraphTest`, on the grounds that the old test "asserts a hand-picked handful
 * of singletons rather than resolving them". That reasoning is right about the old test
 * and it left a hole: with it gone, **nothing in the repository resolved the sync graph
 * at all**, so ADR `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`
 * — which says the resolution test is the one check that catches a DI cycle, because
 * `koin-compiler-plugin` cannot — was left asserting something no longer present.
 *
 * A cycle in this graph is not a compile error. It is a `StackOverflowError` at app
 * start, with a stack whose deepest application frame names an arbitrary line. That is
 * a genuinely expensive class of bug to diagnose, and this test is the cheapest thing
 * that makes it a red build instead.
 *
 * So this is deliberately narrow: it resolves the sync chain and nothing else. It does
 * not try to be a whole-graph test — that was what the deleted one was, and a
 * whole-graph test fails for unrelated reasons until it is curated, at which point it
 * has become a handful again. `GenuiDiGraphTest` owns GenUI; this owns sync. A third
 * domain that needs the same property adds its own, and the shared
 * [desktopPlatformModule] is already checked against its source by
 * `PlatformModuleMirrorTest`, which is what stops the two copies drifting.
 *
 * **`SyncEngine` is the reason this test is not redundant with
 * `SyncEngineTakesNoFeatureTypesTest`.** That one proves the engine's *constructor*
 * names no feature type, statically. This one proves the graph *builds*. Either alone is
 * a half-measure: the static rule cannot see a cycle through any other type, and
 * resolution cannot see one that is never resolved.
 */
@Tag("slow")
class SyncDiGraphResolutionTest {

    @Test
    fun `the sync chain resolves from the composed desktop graph`() {
        val app = koinApplication {
            modules(
                listOf(
                    desktopPlatformModule(),
                    gateModule("https://github.com/singularity-todo/singularity/releases"),
                ) + domainModule(),
            )
        }
        try {
            // Leaf first, then the chain upward. Resolving `SyncEngine` is the assertion
            // that matters: it takes `() -> SyncDocumentWriter`, so the cycle closes only
            // when the writer is asked for, and only a real resolution reaches that.
            assertNotNull(app.koin.get<SyncDocumentWriter>())
            assertNotNull(app.koin.get<SyncRepository>())
            assertNotNull(app.koin.get<SyncEngine>())
            assertNotNull(app.koin.get<SyncWorkScheduler>())
            // The bootstrapper registers a handler per supported document type from inside
            // its own body, so it is the one definition here that can be complete in the
            // module and still fail to construct.
            assertNotNull(app.koin.get<SyncBootstrapper>())
        } finally {
            app.close()
        }
    }
}
