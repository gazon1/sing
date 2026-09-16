package com.singularity.todo.feature.agenda

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class SavedAgendaViewId(val raw: String) {
    companion object {
        fun generate() = SavedAgendaViewId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = SavedAgendaViewId(value)
    }
}
