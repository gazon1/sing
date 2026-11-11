package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every bottom sheet asks the shared sheet state for its state.
 *
 * ## The defect this exists to prevent
 *
 * On the desktop the sheet is a window of its own — Material renders the
 * `ModalBottomSheet` as a `Dialog` there — and the half-expanded anchor hangs
 * half of the sheet *below* that window: measured in a 360x768 desktop window,
 * the search filter sheet's body sat at y=432..1152 and its lower half, the
 * actions among them, was drawn where no one could reach it.
 *
 * `rememberAppSheetState` is where that is answered: on the desktop it takes
 * `PartiallyExpanded` out of the enabled values, everywhere else it passes the
 * caller's values through unchanged.
 *
 * The rule has to be *global* to work. A sheet that builds its own state with
 * `rememberBottomSheetState` keeps the half-expanded anchor, whatever every
 * other sheet does — and Material's own default does the same, because
 * `ModalBottomSheet` defaults to `rememberBottomSheetState(initialValue =
 * Hidden)`, whose enabled values include `PartiallyExpanded`. So a sheet that
 * writes `ModalBottomSheet(onDismissRequest = …)` and says nothing about its
 * state is a sheet that half-expands on the desktop.
 *
 * ## What this does not check
 *
 * Whether the sheet that asks for the factory is *tall* enough for the
 * half-expanded anchor to exist — Material creates that anchor only for a sheet
 * large enough, and a short sheet is unaffected either way. This gate says the
 * state comes from one place; whether the sheet hangs out of the window is a
 * measurement, and the desktop smoke test is that measurement.
 */
@Tag("fast")
class BottomSheetStateFactoryTest {
    /** The one file allowed to build a sheet state itself. */
    private val factoryOwner = "core/ui/components/sheet/BottomSheetHost.kt"

    @Test
    fun `a sheet that draws its own bottom sheet asks the shared factory for the state`() {
        val offenders = mutableListOf<String>()

        SourceScan.productionSources.forEach { source ->
            if (!source.text.contains("ModalBottomSheet(")) return@forEach
            if (source.text.contains("rememberAppSheetState(")) return@forEach
            offenders += source.file.path
        }

        assertTrue(
            offenders.isEmpty(),
            "A sheet that draws its own `ModalBottomSheet` builds the state Material's default " +
                "gives it — and that default keeps the half-expanded anchor the desktop cannot " +
                "carry. Ask `rememberAppSheetState` instead:\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `only the shared factory builds a sheet state`() {
        val offenders = mutableListOf<String>()

        SourceScan.productionSources.forEach { source ->
            if (!source.text.contains("rememberBottomSheetState(")) return@forEach
            if (source.file.path.endsWith(factoryOwner)) return@forEach
            offenders += source.file.path
        }

        assertTrue(
            offenders.isEmpty(),
            "`rememberBottomSheetState` is what `rememberAppSheetState` wraps, and using it " +
                "directly is how a sheet keeps the half-expanded anchor the desktop cannot " +
                "carry:\n" + offenders.joinToString("\n") { "  - $it" },
        )
    }
}
