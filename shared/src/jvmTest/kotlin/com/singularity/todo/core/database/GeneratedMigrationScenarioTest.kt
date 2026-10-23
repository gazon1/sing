package com.singularity.todo.core.database

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The semantics of the migrations, generated from the scenario files.
 *
 * GENERATED FILE — do not edit. The scenarios live in the YAML files under
 * `shared/src/jvmTest/resources/migrations`, and this file is what
 * `python3 scripts/check-room-schema-integrity.py --write` writes from them.
 * Edit the scenario, not this.
 *
 * The round-trip test proves the schema. This proves the meaning: a row planted
 * before the migration, and a value that must hold after it.
 */
@Tag("slow")
@Tag("slow:migration")
class GeneratedMigrationScenarioTest {

    private data class Scenario(
        val from: Int,
        val to: Int,
        val name: String,
        val seed: List<MigrationSeed>,
        val expect: List<MigrationExpect>,
    )

    @ParameterizedTest(name = "v{0} to v{1} - {2}")
    @MethodSource("scenarios")
    fun `the migration keeps its promise`(
        from: Int,
        to: Int,
        name: String,
        seed: List<MigrationSeed>,
        expect: List<MigrationExpect>,
    ) = runTest {
        runMigrationScenario(from, to, name, seed, expect)
    }

    companion object {
        private val SCENARIOS: List<Scenario> = listOf(
            Scenario(
                from = 31,
                to = 32,
                name = "task_id is backfilled into target_id",
                seed = listOf(
                    MigrationSeed.Sql(
                        "INSERT INTO ai_proposal (id, user_id, source, status, target_kind, created_at," +
                            "updated_at, server_version, sync_status) VALUES ('p1', 'u1', 'A', 'PENDING', 'TASK'," +
                            "1, 1, 1, 'PENDING')",
                    ),
                    MigrationSeed.Sql(
                        "UPDATE ai_proposal SET task_id = 't-1' WHERE id = 'p1'",
                    ),
                ),
                expect = listOf(
                    MigrationExpect(
                        sql = "SELECT target_id FROM ai_proposal WHERE id = 'p1'",
                        equals = "t-1",
                    ),
                ),
            ),
            Scenario(
                from = 31,
                to = 32,
                name = "a proposal that already has a target keeps it",
                seed = listOf(
                    MigrationSeed.Sql(
                        "INSERT INTO ai_proposal (id, user_id, source, status, target_kind, target_id," +
                            "created_at, updated_at, server_version, sync_status) VALUES ('p2', 'u1', 'A'," +
                            "'PENDING', 'TASK', 'y-1', 1, 1, 1, 'PENDING')",
                    ),
                ),
                expect = listOf(
                    MigrationExpect(
                        sql = "SELECT target_id FROM ai_proposal WHERE id = 'p2'",
                        equals = "y-1",
                    ),
                ),
            ),
            Scenario(
                from = 31,
                to = 32,
                name = "a proposal with neither target is deleted",
                seed = listOf(
                    MigrationSeed.Sql(
                        "INSERT INTO ai_proposal (id, user_id, source, status, target_kind, created_at," +
                            "updated_at, server_version, sync_status) VALUES ('p3', 'u1', 'A', 'PENDING', 'TASK'," +
                            "1, 1, 1, 'PENDING')",
                    ),
                ),
                expect = listOf(
                    MigrationExpect(
                        sql = "SELECT COUNT(*) FROM ai_proposal WHERE id = 'p3'",
                        equals = "0",
                    ),
                ),
            ),
            Scenario(
                from = 31,
                to = 32,
                name = "the notes survive the rebuild",
                seed = listOf(
                    MigrationSeed.Sql(
                        "INSERT INTO notes (id, user_id, title, is_folder, created_at, updated_at," +
                            "server_version, sync_status) VALUES ('n1', 'u1', 'hello', 0, 1, 1, 1, 'PENDING')",
                    ),
                ),
                expect = listOf(
                    MigrationExpect(
                        sql = "SELECT title FROM notes WHERE id = 'n1'",
                        equals = "hello",
                    ),
                ),
            ),
            Scenario(
                from = 44,
                to = 45,
                name = "a note that links to a task points at it",
                seed = listOf(
                    MigrationSeed.LegacyTask(
                        id = "t1",
                        columns = mapOf(),
                    ),
                    MigrationSeed.Sql(
                        "INSERT INTO notes (id, user_id, title, is_folder, outgoing_links, created_at," +
                            "updated_at, server_version, sync_status) VALUES ('n1', 'u1', 'Note n1', 0," +
                            "'[\"task://t1\"]', 1000, 1000, 0, 'LOCAL_ONLY')",
                    ),
                ),
                expect = listOf(
                    MigrationExpect(
                        sql = "SELECT task_id FROM notes WHERE id = 'n1'",
                        equals = "t1",
                    ),
                ),
            ),
            Scenario(
                from = 44,
                to = 45,
                name = "a note that already pointed at a task keeps that task",
                seed = listOf(
                    MigrationSeed.LegacyTask(
                        id = "t1",
                        columns = mapOf(),
                    ),
                    MigrationSeed.LegacyTask(
                        id = "t2",
                        columns = mapOf(),
                    ),
                    MigrationSeed.Sql(
                        "INSERT INTO notes (id, user_id, title, is_folder, outgoing_links, task_id, created_at," +
                            "updated_at, server_version, sync_status) VALUES ('n2', 'u1', 'Note n2', 0," +
                            "'[\"task://t2\"]', 't1', 1000, 1000, 0, 'LOCAL_ONLY')",
                    ),
                ),
                expect = listOf(
                    MigrationExpect(
                        sql = "SELECT task_id FROM notes WHERE id = 'n2'",
                        equals = "t1",
                    ),
                ),
            ),
            Scenario(
                from = 44,
                to = 45,
                name = "a link the grammar rejects is gone and a link it accepts stays",
                seed = listOf(
                    MigrationSeed.LegacyTask(
                        id = "t3",
                        columns = mapOf(
                            "outgoing_links" to "[\"note://n1\",\"task://bad id\"]",
                        ),
                    ),
                    MigrationSeed.Sql(
                        "INSERT INTO notes (id, user_id, title, is_folder, outgoing_links, created_at," +
                            "updated_at, server_version, sync_status) VALUES ('n3', 'u1', 'Note n3', 0," +
                            "'[\"task://not a valid id\",\"note://n1\"]', 1000, 1000, 0, 'LOCAL_ONLY')",
                    ),
                ),
                expect = listOf(
                    MigrationExpect(
                        sql = "SELECT outgoing_links FROM notes WHERE id = 'n3'",
                        equals = "[\"note://n1\"]",
                    ),
                    MigrationExpect(
                        sql = "SELECT outgoing_links FROM tasks WHERE id = 't3'",
                        equals = "[\"note://n1\"]",
                    ),
                ),
            ),
            Scenario(
                from = 44,
                to = 45,
                name = "a note without links has no task",
                seed = listOf(
                    MigrationSeed.Sql(
                        "INSERT INTO notes (id, user_id, title, is_folder, outgoing_links, created_at," +
                            "updated_at, server_version, sync_status) VALUES ('n4', 'u1', 'Note n4', 0, '[]', 1000," +
                            "1000, 0, 'LOCAL_ONLY')",
                    ),
                ),
                expect = listOf(
                    MigrationExpect(
                        sql = "SELECT task_id FROM notes WHERE id = 'n4'",
                        equals = "null",
                    ),
                ),
            ),
        )

        @JvmStatic
        fun scenarios(): List<Arguments> = SCENARIOS.map {
            Arguments.of(it.from, it.to, it.name, it.seed, it.expect)
        }
    }
}
