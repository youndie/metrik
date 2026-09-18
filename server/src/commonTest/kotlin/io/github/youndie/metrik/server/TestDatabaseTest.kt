package io.github.youndie.metrik.server

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Сторож самой фикстуры.
 *
 * [TestDatabase] обещает две вещи, и обе — ровно то, чего не делала прежняя форма: закрывать пул и
 * не делить файл между экземплярами. Обещание, записанное только в KDoc, держится до первой правки,
 * поэтому оно проверяется здесь — иначе утечка вернётся тихо, а узнают о ней от красного CI на
 * чужом коде.
 */
class TestDatabaseTest {
    @Test
    fun `closing should leave neither a live pool nor a file`() =
        runTest {
            // Given
            val database = TestDatabase("fixture")
            database.db.execute("select 1").getOrThrow()
            assertTrue(database.db.poolSize() > 0, "пул не поднялся, проверять нечего")

            // When
            database.close()

            // Then
            assertEquals(0, database.db.poolSize(), "пул пережил close()")
            assertFalse(FileSystem.SYSTEM.exists(database.path.toPath()), "файл базы остался на диске")
            assertTrue(
                database.db.execute("select 1").isFailure,
                "закрытый пул всё ещё принимает запросы",
            )
        }

    @Test
    fun `two databases of the same name should not share a file`() {
        // Given / When
        val first = TestDatabase("fixture")
        val second = TestDatabase("fixture")

        // Then — иначе пулы соседних экземпляров теста снова сойдутся на одном файле, который
        // каждый из них считает своим и удаляет у другого из-под ног.
        try {
            assertNotEquals(first.path, second.path)
        } finally {
            first.close()
            second.close()
        }
    }
}
