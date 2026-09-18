package io.github.youndie.metrik.agent

import kotlin.test.Test
import kotlin.test.assertEquals

private object SilentSender : MetrikSender {
    override suspend fun send(packet: String) = Unit

    override fun close() = Unit
}

/**
 * Переполнение очереди обязано быть **посчитанным**.
 *
 * Весь договор агента с хостом держится на том, что потери видны: очередь ограничена намеренно,
 * и единственное, чем «замеров не было» отличается от «замеры не влезли», — счётчик
 * [AgentCounters.dropped]. Поэтому проверяется не «счётчик не нулевой», а точное число: счётчик,
 * который считает не то, что потерял канал, — та же слепота, только с цифрой.
 *
 * Тест написан по найденному дефекту: очередь стояла с `BufferOverflow.DROP_LATEST`, а такой канал
 * не отказывает — `trySend` возвращает успех и выбрасывает замер сам. Счётчик оставался нулём
 * при любой потере, и `HotPathBenchmarkTest` печатал этот ноль как доказательство, что потерь нет.
 */
class AgentInboxOverflowTest {
    private val overflow = 500

    @Test
    fun `samples that do not fit the inbox should be counted`() {
        // Given — агент, которого не подняли: очередь никто не разбирает, поэтому она заполняется
        // ровно до ёмкости и ни на замер больше.
        val config =
            MetrikConfig().apply {
                service = "overflow"
                apiKey = "overflow"
                endpoint = "127.0.0.1:1"
                systemMetrics = false
            }
        val agent = MetrikAgent(config, SilentSender, nowMs = { 0L })

        // When
        repeat(INBOX_CAPACITY + overflow) { agent.record("GET", "/x", 2, 5) }

        // Then
        assertEquals(overflow, agent.counters.dropped, "переполнение очереди не дошло до счётчика")
        agent.stop()
    }
}
