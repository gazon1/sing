package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("fast")
class FieldModeTest {

    @Test fun viewIsSingleton() {
        assertEquals(FieldMode.View, FieldMode.View)
    }

    @Test fun editCarriesDraftText() {
        val mode = FieldMode.Edit("draft text")
        assertEquals("draft text", mode.draft)
    }

    @Test fun editWithEmptyDraftRepresentsClearedInput() {
        val mode = FieldMode.Edit("")
        assertTrue(mode.draft.isEmpty())
    }

    @Test fun viewAndEditAreDistinct() {
        val view: FieldMode = FieldMode.View
        val edit: FieldMode = FieldMode.Edit("anything")
        assertTrue(view != edit)
    }
}
