package io.github.youndie.metrik.agent

import io.github.youndie.metrik.wire.encodeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Бюджет горячего пути (M-23).
 *
 * Тест живёт **только в jvmTest** и намеренно не в `commonTest`: на общем CI-раннере нативный
 * debug-бинарь медленнее в разы, и порог, осмысленный для JVM, там ловил не регрессию, а шум
 * раннера. Замер имеет смысл там, где живёт подавляющее большинство Ktor-сервисов.
 *
 * Порог щедрый: тест ловит регрессию на порядок (лишний лок, аллокация строки на запрос, лишняя
 * сериализация). Фактические числа печатаются — они и есть полезная часть.
 */
class HotPathBenchmarkTest {
    private val operations = 200_000

    /**
     * Порция замеров, укладывающаяся в очередь агента.
     *
     * Половина ёмкости, а не вся: за время порции потребитель успевает проснуться не всегда, и
     * запас в половину очереди означает, что даже пропущенный опрос не доводит до потери.
     */
    private val chunk = INBOX_CAPACITY / 2
    private val rounds = 8

    @Test
    fun `aggregating a request should stay well under a microsecond`() {
        // Given
        val aggregator = WindowAggregator()
        val routes = List(50) { "/route/{id}/$it" }
        repeat(operations / 10) { aggregator.record("GET", routes[it % routes.size], 2, 5, 0) }
        aggregator.drain()

        // When
        val elapsed =
            measureTime {
                repeat(operations) { aggregator.record("GET", routes[it % routes.size], encodeStatus(200), 5, 0) }
            }

        // Then
        val perOperation = elapsed / operations
        println("WindowAggregator.record: $perOperation/op")
        assertTrue(perOperation < 2.microseconds, "aggregation got expensive: $perOperation/op")
    }

    /**
     * Горячий путь живого агента — issue #43.
     *
     * Агент здесь **поднят**, и это условие замера, а не деталь установки. У остановленного агента
     * очередь никто не разбирает: после первых [INBOX_CAPACITY] замеров каждый следующий уходит в
     * переполнение, и тест меряет не ту работу, которую делает сервис под нагрузкой, — без
     * потребителя нет ни его пробуждений, ни соперничества за канал, то есть нет ровно того, что
     * на горячем пути и стоит дороже всего. Прежняя редакция теста мерила именно это и оставалась
     * зелёной тем надёжнее, чем больше замеров теряла.
     *
     * Часы настоящие (умолчание конструктора): чтение часов исполняется на каждый запрос и входит
     * в измеряемую цену. Фиксированное `nowMs = { 0L }` вычло бы его из числа.
     *
     * Замер идёт порциями по [chunk] с паузой на разбор очереди между ними: генератор в тесте
     * быстрее любого мыслимого сервиса (порция в 8 192 замера укладывается в полмиллисекунды,
     * а потребитель опрашивает очередь раз в 200 мс), и без пауз очередь переполнится на любом
     * железе. Паузы в измеряемое время не входят.
     *
     * `dropped` — **условие**, а не украшение: замер, часть вызовов которого ушла в переполнение,
     * мерит переполнение, и отличить одно от другого постфактум по числу нельзя.
     */
    @Test
    fun `handing a measurement to the agent should stay cheap`() {
        // Given — то, что реально исполняется в хуке: замер, объект, неблокирующая отправка в канал.
        val config =
            MetrikConfig().apply {
                service = "bench"
                apiKey = "bench"
                endpoint = "127.0.0.1:1"
                systemMetrics = false
            }
        val agent = MetrikAgent(config, NoopSender)
        agent.start()

        try {
            repeat(chunk) { agent.record("GET", "/x", 2, 5) }
            agent.awaitDrain()

            // When
            var elapsed = Duration.ZERO
            repeat(rounds) {
                elapsed += measureTime { repeat(chunk) { agent.record("GET", "/x", 2, 5) } }
                agent.awaitDrain()
            }

            // Then
            val perOperation = elapsed / (rounds * chunk)
            println("MetrikAgent.record: $perOperation/op (dropped=${agent.counters.dropped})")
            assertEquals(0, agent.counters.dropped, "замеры уходили в переполнение: мерился не горячий путь")
            assertTrue(perOperation < 5.microseconds, "hot path got expensive: $perOperation/op")
        } finally {
            agent.stop()
        }
    }

    /**
     * Ждёт, пока потребитель разберёт очередь.
     *
     * Критерий — две итерации цикла окон: первая начинается заведомо после последнего замера
     * порции и разбирает её целиком, вторая доказывает, что первая дошла до конца. Ожидание
     * ограничено: цикл, который не крутится, обязан валить тест, а не тихо превращать замер
     * в измерение переполнения.
     */
    private fun MetrikAgent.awaitDrain() {
        val seen = counters.loops
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (counters.loops < seen + 2) {
            assertTrue(deadline.hasNotPassedNow(), "цикл окон агента не крутится: loops=${counters.loops}")
            Thread.sleep(10)
        }
    }
}

private object NoopSender : MetrikSender {
    override suspend fun send(packet: String) = Unit

    override fun close() = Unit
}
