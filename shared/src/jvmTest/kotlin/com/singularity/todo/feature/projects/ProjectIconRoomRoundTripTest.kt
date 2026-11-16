package com.singularity.todo.feature.projects

import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.ProjectEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A project icon comes back from the database as the string that was written.
 *
 * ## Why this test exists next to `ProjectIconBoundaryTest`
 *
 * The boundary test asserts the *mapper*: a domain icon becomes the column's
 * string and the column's string becomes the domain icon. That is the code we
 * own between the field and the row — and it is all of it, because the column
 * is a plain `String?` with no converter.
 *
 * What it cannot see is the row itself. A converter added to the column later
 * would leave the mapper test green and change what SQLite stores, so the
 * value that reaches the archive, the sync document and the screen would no
 * longer be the value the user typed. This test writes the composed sequences
 * into a real SQLite and reads them back out of it.
 *
 * The bytes are the point: the family, the flag, the keycap and the person with
 * a skin tone are each several code points in one column, and a column that
 * rewrites them would break every boundary above it at once.
 */
@Tag("slow")
@Tag("slow:data")
class ProjectIconRoomRoundTripTest {
    @Test
    fun `a composed icon comes back from the row as the same string`() =
        runTest {
            DatabaseTestFixture.inMemory().use { fixture ->
                val dao = fixture.database().projectDao()

                COMPOSED_PROJECT_ICONS.forEachIndexed { index, icon ->
                    val id = "p$index"
                    dao.upsert(row(id, icon))

                    assertEquals(
                        icon,
                        dao.getByIdForUser(id, USER)?.icon,
                        "the row holds '$icon'",
                    )
                }
            }
        }

    @Test
    fun `a row without an icon has none`() =
        runTest {
            DatabaseTestFixture.inMemory().use { fixture ->
                val dao = fixture.database().projectDao()
                dao.upsert(row("p1", null))

                assertNull(dao.getByIdForUser("p1", USER)?.icon, "there is no icon to read")
            }
        }

    private fun row(
        id: String,
        icon: String?,
    ) = ProjectEntity(
        id = id,
        userId = USER,
        name = "Project $id",
        color = 0xFF3366FF.toInt(),
        icon = icon,
        description = null,
        createdAt = NOW,
        updatedAt = NOW,
        dueDate = null,
        team = null,
        deletedAt = null,
        parentId = null,
        externalId = null,
    )

    private companion object {
        const val USER = "user-a"

        const val NOW = 1_762_000_000_000L
    }
}
