package io.github.youndie.metrik.server

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.metrik.server.db.migrateDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Миграции обязаны применяться к **уже существующей** базе, а не только к пустой.
 *
 * Каждый тест в этом модуле поднимает базу с нуля, поэтому список миграций у них проходит целиком
 * и всегда. Путь, которым идёт боевая база при выкате — `user_version = N` доезжает до `N + 1`, —
 * не проверялся ничем, а это единственный путь, который способен уронить сервис на старте: база в
 * кластере переживает образы, файл остаётся между выкатами.
 *
 * Проверяется на **последней** миграции: её результат откатывается вручную, версия сдвигается на
 * шаг назад, и `migrateDb` обязан довести базу обратно. Новая миграция → сюда дописывается её
 * откат, иначе тест продолжит проверять предпоследнюю и промолчит об этом.
 */
class MigrateTest {
    private suspend fun ISQLite.scalar(sql: String): Long =
        fetchAll(sql)
            .getOrThrow()
            .rows
            .first()
            .get(0)
            .asLong()

    private suspend fun ISQLite.tableExists(name: String): Boolean =
        scalar("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name';") == 1L

    @Test
    fun `the newest migration should apply to a database that already exists`() =
        runTest {
            // Given — база на текущей схеме, откаченная к предыдущей версии: так выглядит файл,
            // переживший прошлый образ. Номер берётся у самой базы, а не пишется числом: иначе он
            // разъедется со списком миграций на первой же новой.
            val database = TestDatabase("migrate")
            val db = database.db
            val head = db.scalar("PRAGMA user_version;")

            db.execute("DROP TABLE agent_windows;").getOrThrow()
            db.execute("PRAGMA user_version = ${head - 1};").getOrThrow()

            // When
            db.migrateDb()

            // Then
            assertEquals(head, db.scalar("PRAGMA user_version;"), "версия схемы не доехала до головы")
            assertEquals(true, db.tableExists("agent_windows"), "миграция не применилась к существующей базе")

            database.close()
        }

    @Test
    fun `migrating a database that is already current should change nothing`() =
        runTest {
            // Given — рестарт пода: тот же файл, тот же образ.
            val database = TestDatabase("migrate")
            val db = database.db
            val head = db.scalar("PRAGMA user_version;")

            // When
            db.migrateDb()
            db.migrateDb()

            // Then — повторный прогон не должен ни падать на «table already exists», ни двигать версию.
            assertEquals(head, db.scalar("PRAGMA user_version;"))

            database.close()
        }
}
