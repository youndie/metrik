package io.github.youndie.metrik.server

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random

private var opened = 0

// Соль на процесс: jvmTest и linuxX64Test гоняют одни и те же классы из commonTest, и счётчик в
// каждом из них начинается с нуля. Пока Gradle не пускает задачи одного модуля рядом, они и так не
// встретятся, но опираться на это не хочется — цена соли нулевая.
private val salt = Random.nextInt(1_000_000)

/**
 * База на один тестовый экземпляр: свой файл и **закрываемый** пул.
 *
 * Появилась после красного CI, где десять тестов `AlertWorkerTest` подряд падали
 * `SQLError: [PoolTimedOut]` при открытии базы. Сама та поломка воспроизведена не была, а вот
 * устройство фикстуры, на котором она случилась, — дефект независимо от неё:
 *
 * 1. `openDatabase` стоял в инициализаторе свойства, то есть пул открывался **на каждый экземпляр
 *    теста**. У Kotlin/Native экземпляр создаётся на тест, так что класс из десяти тестов открывал
 *    десять пулов по два соединения.
 * 2. Ни один из них не закрывался: `@AfterTest` удалял файл, а пул — нет. У `ConnectionPool` есть
 *    `close()`, и его просто никто не звал.
 * 3. Путь был один на класс и зашит строкой, поэтому все эти пулы жили на одном файле, который
 *    вдобавок удалялся у них из-под ног между тестами.
 *
 * Здесь чинится всё три: путь уникален на экземпляр, пул закрывается, файл удаляется после
 * закрытия, а не до.
 *
 * Новым тестам с базой полагается брать её отсюда, а не звать `openDatabase` напрямую: иначе
 * форма возвращается по одному классу за раз — так уже было, `IngestServiceTest` однажды получил
 * уникальный путь, а остальные шесть классов остались как были.
 */
internal class TestDatabase(
    name: String,
) {
    val path: String = "/tmp/metrik-$name-test-$salt-${opened++}.db"
    val db: ISQLite = openDatabase(path)

    /**
     * Закрывает пул и убирает файл.
     *
     * `getOrThrow`, а не выброшенный `Result`: незакрывшийся пул — это ровно то, что здесь
     * чинится, и узнать о нём надо от упавшего теста, а не от следующего красного CI.
     */
    fun close() {
        runBlocking { db.close().getOrThrow() }
        FileSystem.SYSTEM.delete(path.toPath(), mustExist = false)
    }
}
