package com.singularity.todo.core.sync

/**
 * Document types that can be synchronized.
 * Matches the Flutter sync_core DocType.keys.
 */
enum class DocType(val key: String) {
    Task("task"),
    Note("note"),
    Project("project"),
    Tag("tag");

    companion object {
        fun fromKey(key: String): DocType = entries.first { it.key == key }
    }
}
