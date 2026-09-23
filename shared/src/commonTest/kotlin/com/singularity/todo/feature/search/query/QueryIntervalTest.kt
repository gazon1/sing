package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.search.query.QueryInterval
import com.singularity.todo.feature.search.query.QueryInterval.Companion.NONE
import com.singularity.todo.feature.search.query.QueryInterval.Companion.NOW
import com.singularity.todo.feature.search.query.QueryInterval.Companion.TODAY
import com.singularity.todo.feature.search.query.QueryInterval.Companion.TOMORROW
import com.singularity.todo.feature.search.query.QueryInterval.Companion.YESTERDAY
import com.singularity.todo.feature.search.query.QueryInterval.Companion.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryIntervalTest {

    @Test
    fun `NONE and NOW have correct day values`() {
        assertTrue(NONE.isNone)
        assertEquals(0, NOW.days)
        assertEquals(0, TODAY.days)
        assertEquals(1, TOMORROW.days)
        assertEquals(-1, YESTERDAY.days)
    }

    @Test
    fun `NONE sentinel is distinct from zero`() {
        assertTrue(NONE.isNone)
        assertFalse(NOW.isNone)
    }

    @Test
    fun `days factory produces correct values`() {
        assertEquals(3, QueryInterval(3).days)
        assertEquals(-7, QueryInterval(-7).days)
        assertEquals(30, QueryInterval(30).days)
        assertEquals(365, QueryInterval(365).days)
    }

    @Test
    fun `parse accepts numeric suffixes`() {
        assertEquals(3, parse("3d")?.days)
        assertEquals(-5, parse("-5d")?.days)
        assertEquals(7, parse("1w")?.days)
        assertEquals(-14, parse("-2w")?.days)
        assertEquals(30, parse("1m")?.days)
        assertEquals(90, parse("3m")?.days)
        assertEquals(365, parse("1y")?.days)
        assertEquals(-730, parse("-2y")?.days)
    }

    @Test
    fun `parse accepts named aliases`() {
        assertEquals(0, parse("today")?.days)
        assertEquals(0, parse("tod")?.days)
        assertEquals(0, parse("now")?.days)
        assertEquals(1, parse("tomorrow")?.days)
        assertEquals(1, parse("tom")?.days)
        assertEquals(-1, parse("yesterday")?.days)
    }

    @Test
    fun `parse accepts none alias`() {
        assertTrue(parse("none")?.isNone == true)
        assertTrue(parse("no")?.isNone == true)
    }

    @Test
    fun `parse returns null for unknown strings`() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("foo"))
        assertNull(parse("3x"))      // unknown unit
        assertNull(parse("d"))       // no number
        assertNull(parse("-w"))      // no number
    }

    @Test
    fun `parse trims whitespace`() {
        assertEquals(3, parse("  3d  ")?.days)
        assertEquals(-7, parse("  -1w  ")?.days)
    }

    @Test
    fun `parse is case-insensitive for aliases`() {
        assertEquals(0, parse("TODAY")?.days)
        assertEquals(1, parse("TOMORROW")?.days)
        assertEquals(-1, parse("YESTERDAY")?.days)
    }
}
