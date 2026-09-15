package com.singularity.todo.core.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for [TagPickerSheet].
 *
 * Owns all domain state: the reactive tag list, selection set, inline-create
 * form state, and the tag creation logic. The Composable subscribes rather
 * than calling repositories directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagPickerViewModel(
    private val tagsRepo: TagsRepository,
    private val settingsRepo: SettingsRepository,
    initialSelectedTagIds: Set<String> = emptySet(),
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(0) },
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    // ─── UI State ───────────────────────────────────────────────────────────────

    private val _selected = MutableStateFlow(initialSelectedTagIds)
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _newTagName = MutableStateFlow("")
    val newTagName: StateFlow<String> = _newTagName.asStateFlow()

    /** Reactive tag list — re-fetches when user changes. */
    val tags: StateFlow<List<Tag>> = settingsRepo.userId
        .flatMapLatest { uid -> tagsRepo.watchTags(uid) }
        .stateIn(scope, sharingStarted(), emptyList())

    // ─── Actions ────────────────────────────────────────────────────────────────

    fun toggleTag(tagId: String) {
        _selected.value = if (_selected.value.contains(tagId)) {
            _selected.value - tagId
        } else {
            _selected.value + tagId
        }
    }

    fun setNewTagName(name: String) {
        _newTagName.value = name
    }

    fun setCreating(on: Boolean) {
        _isCreating.value = on
        if (!on) {
            _newTagName.value = ""
        }
    }

    suspend fun createTags() {
        val names = _newTagName.value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (names.isEmpty()) return

        val uid = settingsRepo.userId.first()
        for (name in names) {
            val tag = Tag(
                id = TagId.generate(),
                name = name,
                color = 0xFF9E9E9E.toInt(),
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
                userId = uid,
            )
            tagsRepo.create(tag)
            _selected.value = _selected.value + tag.id.value
        }
        _newTagName.value = ""
        _isCreating.value = false
    }
}
