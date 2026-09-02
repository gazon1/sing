package com.singularity.todo.core.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileChecksumTest {

    @Test
    fun `sha256 produces correct hash for known input`() {
        // SHA-256 of "hello"
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(expected, FileChecksum.sha256("hello".toByteArray()))
    }

    @Test
    fun `sha256 produces consistent hashes`() {
        val data = "test data".toByteArray()
        val hash1 = FileChecksum.sha256(data)
        val hash2 = FileChecksum.sha256(data)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `sha256 produces different hashes for different inputs`() {
        val hashA = FileChecksum.sha256("a".toByteArray())
        val hashB = FileChecksum.sha256("b".toByteArray())
        assertEquals(false, hashA == hashB)
    }

    @Test
    fun `sha256 produces 64-char hex string`() {
        val hash = FileChecksum.sha256("test".toByteArray())
        assertEquals(64, hash.length)
        assertEquals(true, hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `sha256 of empty array produces known hash`() {
        // SHA-256 of empty input
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, FileChecksum.sha256(ByteArray(0)))
    }
}
