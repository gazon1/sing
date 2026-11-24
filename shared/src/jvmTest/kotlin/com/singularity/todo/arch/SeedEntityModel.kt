package com.singularity.todo.arch

import com.singularity.todo.core.seed.SeedCount
import com.singularity.todo.core.ui.TestTags
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * What a seed link writes: the entities it creates, and the ways a flow can
 * name them.
 *
 * The model is what the seeder does, read from the query the link carries — so
 * the gate knows which row a link wrote, which section it is in, and which of
 * them the helper may wait for.
 */
internal object SeedEntityModel {
    /**
     * The parameters of a seed query.
     *
     * A parameter with a blank key or a blank value is dropped, the way the
     * seeder drops it: the seeder answers a link with `task=` by writing
     * nothing, and a model that kept the pair would say the link wrote a task
     * with an empty title — and the gate would hold a flow to a wait for a
     * row that was never written.
     */
    fun parseQuery(query: String): Map<String, String> =
        query
            .split("&")
            .filter { it.contains("=") }
            .map { pair -> pair.substringBefore("=").trim() to decode(pair.substringAfter("=")) }
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .toMap()

    /**
     * Decodes a query value the way the seeder reads it.
     *
     * Android's `Uri` does not read a `+` as a space, so `due=+1d` is "+1d" for
     * the seeder — and `URLDecoder`, which is written for form encoding, would
     * have read it as " 1d".
     */
    private fun decode(raw: String): String {
        if (!raw.contains('%') && !raw.contains('+')) return raw
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
    }

    /**
     * The entity a seed query creates, and the ways a flow can name it.
     *
     * The row of a title-named entity is the app's own slug of that title, so
     * the ids are produced by `TestTags` and not by a second copy of the slug
     * rule living in this test.
     *
     * The container is the agenda section the entity is in. A section renders
     * because it holds a task, so waiting for it is waiting for the entity —
     * as long as it is the section the entity belongs to.
     *
     * The names are what the entity is called. A name is a handle of its own:
     * the renamed project is on screen under its new name, and a wait for that
     * name is a wait for the entity — the one wait that can tell a rename
     * from the row it renamed.
     *
     * A seed that writes several rows writes several *entities*: `count=3` is
     * three tasks, and each of them is a row a flow may assert on. They are
     * kept apart here, because one wait for one of them says nothing about
     * the others — the entities are what the rule holds a flow to.
     */
    fun seededEntity(params: Map<String, String>): Seeded {
        val entities = mutableListOf<Entity>()
        // A link that changes the session, cleans up or resets writes no
        // seed kind: the seeder answers it and returns before it writes
        // anything (`DebugSeedActivity.seed`). Such a link names no seed kind
        // to wait for, whatever else its query carries.
        val writesNothing = params.keys.any { key -> key in NO_ENTITY_KEYS }
        if (!writesNothing) {
            seedKinds(params, entities)
        }
        // A profile is not a seed kind: the seeder switches to it — and creates
        // it when it does not exist yet — before it writes anything else, so a
        // link that names a profile brings a profile into being whatever else
        // its query says. The switch happens before the seeder answers a
        // reset, a cleanup or a session link, so the profile is an entity of
        // such a link as well.
        params["profile"]?.let { name ->
            entities += Entity(listOf(TestTags.profileItem(name)), listOf(name))
        }
        return Seeded(entities, entities.flatMap { entity -> entity.containers }.distinct())
    }

    /**
     * The kinds a link can seed, written one kind at a time.
     *
     * A link seeds at most one kind: the seeder takes the first kind the
     * query names (`DebugSeedActivity.seedEntity`). A query that names a task
     * and a note seeds the task, and the note is never written — so the model
     * writes one kind too, or the gate would hold a flow to a wait for a row
     * that does not exist.
     */
    private fun seedKinds(
        params: Map<String, String>,
        entities: MutableList<Entity>,
    ) {
        when {
            params.containsKey("task") -> {
                val title = params["task"].orEmpty()
                // The count the seeder reads: an invalid one writes nothing at
                // all, so such a seed creates no entity.
                val count = SeedCount.parse(params["count"])
                if (count != null) {
                    val landing = isLandingDue(params["due"])
                    val overdue = isOverdue(params["due"])
                    val rows = mutableListOf<Entity>()
                    if (count > 1) {
                        (1..count).forEach { index ->
                            rows +=
                                Entity(
                                    listOf(TestTags.taskItem("$title-$index")),
                                    listOf("$title-$index"),
                                    // A row of a multi-row seed is not there to
                                    // be waited for: the list the seed filled is
                                    // longer than the screen, so the row may be
                                    // below the fold. The helper does not
                                    // scroll — the flow waits for the row.
                                    helperWaitable = false,
                                )
                        }
                    } else {
                        rows +=
                            Entity(
                                listOf(TestTags.taskItem(title)),
                                listOf(title),
                                // An overdue row is not there to be waited for
                                // either: the app collapses the overdue
                                // section once the profile holds enough
                                // overdue tasks, and the helper neither
                                // expands the section nor scrolls to the row.
                                // The section itself is a target — its header
                                // is rendered whether the section is expanded
                                // or collapsed.
                                helperWaitable = landing && !overdue,
                            )
                    }
                    // The section the rows are in. It belongs to these rows
                    // and to nothing else: a wait for it is a wait for the
                    // entities of this seed, and not for another entity the
                    // same link may have written.
                    val section = dueSection(params["due"])?.let { name -> TestTags.agendaSection(name) }
                    entities += rows.map { entity -> entity.copy(containers = listOfNotNull(section)) }
                }
            }

            params.containsKey("note") -> {
                val title = params["note"].orEmpty()
                entities += Entity(listOf(TestTags.noteItemByTitle(title)), listOf(title))
            }

            params.containsKey("project") -> {
                val name = params["project"].orEmpty()
                val rows = mutableListOf(TestTags.projectCard(name))
                params["id"]?.let { id -> rows += TestTags.menuProjectNode(id) }
                entities += Entity(rows, listOf(name))
            }

            params.containsKey("projects") -> {
                val count = params["projects"]?.toIntOrNull() ?: 0
                (1..count).forEach { index ->
                    val number = index.toString().padStart(3, '0')
                    entities +=
                        Entity(
                            listOf(
                                TestTags.projectCard("Bulk $number"),
                                TestTags.menuProjectNode("$BULK_ID_PREFIX-$number"),
                            ),
                            listOf("Bulk $number"),
                        )
                }
            }
        }
    }

    /**
     * Whether a due date puts the task on the screen the app is left on.
     *
     * The app comes up on the agenda's Today tab, and that tab holds the
     * overdue and today's tasks. A task due later is in the Upcoming tab, a
     * task without a date is in the Inbox, and neither is on screen.
     *
     * A date the seed resolves against the device's own "today" — `YYYY-MM-DD`
     * — is not decidable here: it is overdue on one day and upcoming on
     * another. Such a task is treated as not on the screen, and the flow that
     * seeds it waits for it itself.
     *
     * A date of zero days is today whatever its sign says: the seeder reads
     * `+0d` as today — every sign but the negative one is positive — and so
     * does the gate. A date before today is on the screen as well: the Today
     * tab holds the overdue tasks.
     */
    private fun isLandingDue(due: String?): Boolean {
        val expr = due?.lowercase() ?: return false
        if (expr == "today") return true
        val relative = RELATIVE_DAYS.matchEntire(expr) ?: return false
        val sign = relative.groupValues[1]
        val days = relative.groupValues[2]
        return days == "0" || sign == "-"
    }

    /**
     * Whether a due date puts the task in the overdue section.
     *
     * A task due in the past is overdue — whatever day it was due on — and the
     * Today tab holds it in the Overdue section.
     */
    private fun isOverdue(due: String?): Boolean {
        val expr = due?.lowercase() ?: return false
        val relative = RELATIVE_DAYS.matchEntire(expr) ?: return false
        return relative.groupValues[1] == "-" && relative.groupValues[2] != "0"
    }

    /**
     * The agenda section a task is in, by its due date.
     *
     * Only the sections that are named the same way whatever the day is:
     * Overdue, Today, Tomorrow. A task due further out is grouped under
     * its date, and that name is not knowable from the query — so such a
     * task is waited for by its row.
     */
    private fun dueSection(due: String?): String? {
        val expr = due?.lowercase() ?: return null
        val relative = RELATIVE_DAYS.matchEntire(expr)
        val sign = relative?.groupValues?.get(1) ?: ""
        val days = relative?.groupValues?.get(2) ?: ""
        return when {
            expr == "today" -> "Today"
            expr == "tomorrow" -> "Tomorrow"
            relative == null -> null
            sign == "-" && days != "0" -> "Overdue"
            days == "0" -> "Today"
            days == "1" -> "Tomorrow"
            else -> null
        }
    }

    /**
     * What a seed writes: the entities it created, and the containers they are in.
     *
     * The containers are the union of the containers of the entities: a section
     * that holds a thousand overdue rows holds every one of them, and a wait
     * for that section is a wait for all of them.
     */
    data class Seeded(val entities: List<Entity>, val containers: List<String>)

    /** One entity the seed wrote: the rows it is rendered under, the names it is called. */
    data class Entity(
        val rows: List<String>,
        val names: List<String>,
        /**
         * Whether the helper may wait for this entity's row.
         *
         * The helper waits on the agenda's Today tab — the screen the seed
         * leaves the app on — and it does not scroll. So the row it may wait
         * for is a row of that screen that is there to be seen: a task that is
         * due today, and the only row of its seed. A row of a multi-row seed
         * is below the fold of the list the seed itself filled, an overdue
         * row is in a section the app collapses once the profile holds enough
         * overdue tasks, a task in another tab, a note, a project and a
         * profile are on screens the app is not on — the helper's wait would
         * run out its timeout on any of them.
         */
        val helperWaitable: Boolean = false,
        /**
         * The containers this entity is in — the agenda sections that hold its
         * row.
         *
         * The containers belong to the entity and not to the seed: a link that
         * writes a task and a profile writes a section for the task and
         * nothing for the profile, and a wait for that section is a wait for
         * the task and not for the profile.
         */
        val containers: List<String> = emptyList(),
    )

    /** The prefix of the ids the bulk project seed writes, as `SeedWriter` writes them. */
    const val BULK_ID_PREFIX = "bulk"

    /**
     * The query keys that make a link write no entity.
     *
     * The seeder answers them and returns before it writes anything: a link
     * that cleans up, resets or changes the session carries no entity params
     * of its own.
     */
    val NO_ENTITY_KEYS = setOf("reset", "cleanup", "session")

    /** A due date relative to the device's own today: `-1d`, `+2d`, `0d`. */
    val RELATIVE_DAYS = Regex("""^([+-]?)(\d+)d$""")
}
