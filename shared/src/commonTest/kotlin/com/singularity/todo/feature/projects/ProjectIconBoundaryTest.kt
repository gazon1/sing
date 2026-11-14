package com.singularity.todo.feature.projects

import com.singularity.todo.core.backup.toDto
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import com.singularity.todo.core.backup.toEntity as dtoToEntity
import com.singularity.todo.feature.projects.data.toEntity as projectToEntity
import com.singularity.todo.feature.projects.data.toProject as entityToProject

/**
 * A project icon crosses every persistence and wire boundary as the exact string it is.
 *
 * The domain types `Project.icon` as `Emoji?` (#2934), but the Room column, the backup
 * DTO and the sync document keep nullable Unicode strings. None of those boundaries
 * converts or validates: what the editor stored is what the row, the archive and the
 * server carry. This test pins that for a missing icon, every retired Material key,
 * unknown text and composed sequences, so a boundary that starts normalizing, parsing
 * or dropping a value fails here.
 *
 * The legacy policy is "keep the string": a stored `"Work"` is *not* mapped to 💼. See
 * ADR `2026-11-14-project-icons-are-emoji-or-nothing`.
 */
@Tag("fast")
class ProjectIconBoundaryTest {
    @Test
    fun `an absent icon stays absent at every boundary`() {
        val project = project(icon = null)

        assertNull(project.projectToEntity().icon, "the Room row has no icon")
        assertNull(project.projectToEntity().entityToProject().icon, "and the domain reads none")

        val dto = project.projectToEntity().toDto()
        assertNull(dto.icon, "the backup carries no icon")
        assertNull(dto.dtoToEntity(USER).icon, "and the restore writes none")

        assertEquals(JsonNull, project.toJson()["icon"], "the sync document has no icon")
        assertNull(
            StableJson.decodeFromJsonElement(Project.serializer(), project.toJson()).icon,
            "and the pull reads none",
        )
    }

    @Test
    fun `a stored value crosses Room, backup and sync verbatim`() {
        STORED_PROJECT_ICONS.forEach { raw ->
            val icon = Emoji(raw)
            val project = project(icon = icon)

            assertEquals(raw, project.projectToEntity().icon, "Room keeps '$raw'")
            assertEquals(icon, project.projectToEntity().entityToProject().icon, "and reads it back")

            val dto = project.projectToEntity().toDto()
            assertEquals(raw, dto.icon, "the backup keeps '$raw'")
            assertEquals(raw, dto.dtoToEntity(USER).icon, "and the restore writes it back")

            val json = project.toJson()
            assertEquals(raw, json["icon"]?.jsonPrimitive?.content, "the document carries '$raw'")
            assertEquals(
                icon,
                StableJson.decodeFromJsonElement(Project.serializer(), json).icon,
                "and the pull decodes it unchanged",
            )
        }
    }

    @Test
    fun `every retired Material key is not converted on any boundary`() {
        // The picker writes Emoji now, so no code can produce a Material key; the
        // values in old rows and from old clients are still there. The decision is to
        // leave them alone rather than invent a glyph: "Work" stays "Work" everywhere
        // and the renderer draws it as its own text (ProjectIdentityTest). Each key is
        // checked, so a mapping added for one of them cannot hide behind the others.
        LEGACY_MATERIAL_KEYS.forEach { key ->
            val project = project(icon = Emoji(key))
            val entity = project.projectToEntity()

            assertEquals(key, entity.entityToProject().icon?.value, "Room keeps '$key'")
            assertEquals(key, entity.toDto().icon, "the backup keeps '$key'")
            assertEquals(key, project.toJson()["icon"]?.jsonPrimitive?.content, "the document carries '$key'")
        }
    }

    @Test
    fun `a blank icon is a value, not a missing one`() {
        val project = project(icon = Emoji("   "))

        assertEquals("   ", project.projectToEntity().icon, "the row keeps the blank string")
        assertEquals("   ", project.projectToEntity().toDto().icon, "the backup keeps it")
    }

    private fun project(icon: Emoji?) =
        Project(
            id = ProjectId.fromString("p1"),
            userId = UserId(USER),
            name = "Project",
            color = 0xFF112233.toInt(),
            icon = icon,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private companion object {
        const val USER = "user-a"

        val NOW: Instant = Instant.parse("2026-11-14T00:00:00Z")
    }
}
