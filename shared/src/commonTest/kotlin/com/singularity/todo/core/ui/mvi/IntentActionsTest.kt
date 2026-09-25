package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.ui.IntentActions
import com.singularity.todo.core.ui.MviIntent
import kotlin.test.Test
import kotlin.test.assertEquals

sealed interface DummyIntent : MviIntent {
    data class Delete(val id: String) : DummyIntent
}

class IntentActionsTest {
    @Test
    fun `invoke dispatches intent to wrapped function`() {
        var dispatched: DummyIntent? = null
        val actions = IntentActions<DummyIntent> { dispatched = it }

        actions(DummyIntent.Delete("abc"))
        assertEquals(DummyIntent.Delete("abc"), dispatched)
    }

    @Test
    fun `invoke returns Unit`() {
        val actions = IntentActions<DummyIntent> { }
        val result = actions(DummyIntent.Delete("x"))
        assertEquals(Unit, result)
    }
}
