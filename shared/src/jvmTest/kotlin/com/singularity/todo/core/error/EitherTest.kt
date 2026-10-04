package com.singularity.todo.core.error

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Tag("fast")
class EitherTest {

    @Test
    fun `Left isLeft returns true`() {
        val result: Either<String, Int> = Either.Left("error")
        assertTrue(result.isLeft)
        assertFalse(result.isRight)
    }

    @Test
    fun `Right isRight returns true`() {
        val result: Either<String, Int> = Either.Right(42)
        assertTrue(result.isRight)
        assertFalse(result.isLeft)
    }

    @Test
    fun `fold with Left calls left branch`() {
        val result: Either<String, Int> = Either.Left("error")
        val folded = result.fold(
            left = { "LEFT: $it" },
            right = { "RIGHT: $it" },
        )
        assertEquals("LEFT: error", folded)
    }

    @Test
    fun `fold with Right calls right branch`() {
        val result: Either<String, Int> = Either.Right(42)
        val folded = result.fold(
            left = { "LEFT: $it" },
            right = { "RIGHT: $it" },
        )
        assertEquals("RIGHT: 42", folded)
    }

    @Test
    fun `getOrElse with Left returns default`() {
        val result: Either<String, Int> = Either.Left("error")
        val value = result.getOrElse { -1 }
        assertEquals(-1, value)
    }

    @Test
    fun `getOrElse with Right returns value`() {
        val result: Either<String, Int> = Either.Right(42)
        val value = result.getOrElse { -1 }
        assertEquals(42, value)
    }

    @Test
    fun `map transforms Right value`() {
        val result: Either<String, Int> = Either.Right(21)
        val mapped = result.map { it * 2 }
        assertIs<Either.Right<Int>>(mapped)
        assertEquals(42, (mapped as Either.Right).value)
    }

    @Test
    fun `map does not transform Left`() {
        val result: Either<String, Int> = Either.Left("error")
        val mapped = result.map { it * 2 }
        assertIs<Either.Left<String>>(mapped)
        assertEquals("error", (mapped as Either.Left).error)
    }

    @Test
    fun `mapError transforms Left error`() {
        val result: Either<String, Int> = Either.Left("error")
        val mapped = result.mapError { it.uppercase() }
        assertIs<Either.Left<String>>(mapped)
        assertEquals("ERROR", (mapped as Either.Left).error)
    }

    @Test
    fun `mapError does not transform Right`() {
        val result: Either<String, Int> = Either.Right(42)
        val mapped = result.mapError { it.uppercase() }
        assertIs<Either.Right<Int>>(mapped)
        assertEquals(42, (mapped as Either.Right).value)
    }

    @Test
    fun `toResult converts Left to Failure`() {
        val either: Either<AppError.Validation, Int> = Either.Left(AppError.Validation("bad input"))
        val result = either.toResult()
        assertTrue(result.isFailure)
        assertEquals("bad input", result.exceptionOrNull()?.message)
    }

    @Test
    fun `toResult converts Right to Success`() {
        val either: Either<AppError.Validation, Int> = Either.Right(42)
        val result = either.toResult()
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }
}
