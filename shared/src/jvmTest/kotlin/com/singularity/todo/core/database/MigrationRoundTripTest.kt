package com.singularity.todo.core.database

import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The migration chain, one version at a time.
 *
 * GENERATED FILE — do not edit it by hand. Regenerate it with:
 *
 *     python3 scripts/check-room-schema-integrity.py --write
 *
 * The gate that owns this file fails when it is not what the generator produces,
 * so a migration that nobody regenerated for is a red build rather than a gap
 * nobody notices.
 *
 * ## What one case does
 *
 * It takes the exported schema of one version, opens that database through Room
 * and lets the whole chain run. Room validates the schema it finds after the
 * chain against the entities the code declares, so the case fails when a
 * migration produces something other than the schema its successor claims —
 * the one property no gate can establish by reading files.
 *
 * The fixture carries one row in `tasks`, so "the schema came out right" and
 * "the data is still there" are a single case.
 *
 * ## Why one parameterized case and not one method per version
 *
 * A method per version is the shape that reads best and the shape detekt
 * refuses: forty methods in one class is forty functions, and the bar for a
 * class is sixteen. A parameterized case keeps the file at one function, keeps
 * one reported case per version — which is what the CI annotations want — and
 * keeps the count honest, because the list of versions is generated.
 */
@Tag("slow")
class MigrationRoundTripTest {

    @ParameterizedTest(name = "the upgrade from v{0} lands on the head schema")
    @ValueSource(
        ints = [
            5,
            6,
            7,
            8,
            9,
            10,
            11,
            12,
            13,
            14,
            15,
            16,
            17,
            18,
            19,
            20,
            21,
            22,
            23,
            24,
            25,
            26,
            27,
            28,
            29,
            30,
            31,
            32,
            33,
            34,
            35,
            36,
            37,
            38,
            39,
            40,
            41,
            42,
            43,
            44,
            45,
        ],
    )
    fun `the upgrade lands on the head schema`(version: Int) {
        roundTripFrom(version)
    }
}
