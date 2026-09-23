package com.singularity.todo.feature.search.domain

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class SavedSearchId(val raw: String) {
    companion object {
        fun generate() = SavedSearchId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = SavedSearchId(value)
    }
}
