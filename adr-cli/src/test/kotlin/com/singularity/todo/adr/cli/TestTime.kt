package com.singularity.todo.adr.cli

import com.singularity.todo.core.platform.TimeZoneProvider
import kotlinx.datetime.TimeZone

/**
 * A fixed [TimeZoneProvider] for the CLI tests, UTC.
 *
 * Declared here rather than imported from `:shared`, because `:adr-cli` deliberately
 * does not depend on `:shared` — that is the edge this module exists to cut. Sharing
 * the constant would have meant re-introducing the whole application as a test
 * dependency, and then the 4.9 MB distribution would be a test-only fiction.
 *
 * UTC rather than a plausible local zone on purpose: a test that means "midnight rolls
 * over" wants the boundary to be unambiguous, and a test that means a specific offset
 * should say so at the call site rather than inheriting one.
 */
val TEST_TZ: TimeZoneProvider = object : TimeZoneProvider {
    override fun current(): TimeZone = TimeZone.UTC
}
