package dev.telegrammcp.server.client

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Multi-account isolation invariant: no two accounts may share or nest TDLib
 * database directories or downloads directories in any combination.
 */
class TdLibStorageIsolationTest {

    private fun storage(label: String, database: String, downloads: String) =
        AccountStorage(label, Path.of(database), Path.of(downloads))

    @Test
    fun `disjoint per-account directories pass`() {
        assertDoesNotThrow {
            validateDistinctAccountStorage(
                listOf(
                    storage("personal", "/data/tdlib/personal", "/data/tdlib/personal/downloads"),
                    storage("work", "/data/tdlib/work", "/data/tdlib/work/downloads"),
                ),
            )
        }
    }

    @Test
    fun `nested database directories fail`() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateDistinctAccountStorage(
                listOf(
                    storage("personal", "/data/tdlib", "/data/downloads/personal"),
                    storage("work", "/data/tdlib/work", "/data/downloads/work"),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("database directories"))
    }

    @Test
    fun `shared downloads directory fails`() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateDistinctAccountStorage(
                listOf(
                    storage("personal", "/data/tdlib/personal", "/data/downloads"),
                    storage("work", "/data/tdlib/work", "/data/downloads"),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("downloads directories"))
    }

    @Test
    fun `downloads directory inside another account's database directory fails`() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateDistinctAccountStorage(
                listOf(
                    storage("personal", "/data/tdlib/personal", "/data/tdlib/work/files"),
                    storage("work", "/data/tdlib/work", "/data/tdlib/work/downloads"),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("downloads"))
    }
}
