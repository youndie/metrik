package ru.workinprogress.metrik.agent

import ru.workinprogress.metrik.wire.encodeStatus
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
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
        val agent = MetrikAgent(config, NoopSender, nowMs = { 0L })
        repeat(operations / 10) { agent.record("GET", "/x", 2, 5) }

        // When
        val elapsed = measureTime { repeat(operations) { agent.record("GET", "/x", 2, 5) } }

        // Then
        val perOperation = elapsed / operations
        println("MetrikAgent.record: $perOperation/op (dropped=${agent.counters.dropped})")
        assertTrue(perOperation < 5.microseconds, "hot path got expensive: $perOperation/op")
    }
}

private object NoopSender : MetrikSender {
    override suspend fun send(packet: String) = Unit

    override fun close() = Unit
}
