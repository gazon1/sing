/*
 * Copyright 2025 New Vector Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import kotlin.time.Instant

/**
 * Factory for [SavedAgendaView] construction.
 *
 * Top-level functions (not extension functions on [SavedAgendaView]) to avoid
 * KMP metadata compilation issues where companion-object extensions are
 * unresolved in JVM test source sets.
 */
object SavedAgendaViewFactory {

    /**
     * Creates a new [SavedAgendaView] for first-time save (Create mode).
     * Generates a fresh [SavedAgendaViewId] and sets [createdAt] = [updatedAt].
     */
    fun create(userId: UserId, name: String, sectionsJson: String, now: Instant): SavedAgendaView = SavedAgendaView(
        id = SavedAgendaViewId.generate(),
        userId = userId,
        name = name,
        sectionsJson = sectionsJson,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * Updates an existing [SavedAgendaView] with new name and sections.
     * Preserves [id], [userId], and [createdAt].
     */
    fun update(source: SavedAgendaView, name: String, sectionsJson: String, now: Instant): SavedAgendaView =
        source.copy(
            name = name,
            sectionsJson = sectionsJson,
            updatedAt = now,
        )

    /**
     * Duplicates a [SavedAgendaView] into a different user profile.
     * Generates a fresh [id], sets [userId] to [targetUserId], resets timestamps.
     */
    fun duplicateForProfile(source: SavedAgendaView, targetUserId: UserId, now: Instant): SavedAgendaView = source.copy(
        id = SavedAgendaViewId.generate(),
        userId = targetUserId,
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * Encodes this definition into the stored `sectionsJson` column format.
 *
 * The single encode site for the view's section payload — the decode half lives
 * in the view model (`decodeSections`), and test fixtures seed views through
 * this function so both halves stay format-coupled in one place.
 */
fun AgendaDefinition.toSectionsJson(): String = StableJson.encodeToString(AgendaDefinition.serializer(), this)

/**
 * The sections of a saved view, or null when the stored blob cannot be decoded.
 *
 * A saved view is one JSON blob, so one selector the registry no longer knows takes
 * the whole view down. Callers use a null to *drop* the view rather than show a
 * blank screen: the rest of the list stays usable, and the broken row does not
 * pretend to be an empty agenda. An absent blob is an empty section list — a view
 * that never had sections is still a view.
 */
fun decodeSavedSections(json: String?): List<Section>? = if (json == null) {
    emptyList()
} else {
    runCatching {
        StableJson.decodeFromString<AgendaDefinition>(json).sections
    }.getOrNull()
}
