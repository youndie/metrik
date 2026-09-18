package io.github.youndie.metrik.agent

import io.github.youndie.metrik.wire.Frame
import io.github.youndie.metrik.wire.MetrikJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OVERFLOW = 7

private class CollectingSender : MetrikSender {
    val packets = mutableListOf<String>()

    override suspend fun send(packet: String) {
        packets += packet
    }

    override fun close() = Unit
}

/**
 * Потери агента обязаны доехать до сервера.
 *
 * До этого они жили только внутри процесса (`AgentCounters`), а на проводе их не было вовсе —
 * значит на дашборде окно с потерями выглядело как окно с меньшей нагрузкой, и отличить одно от
 * другого было нечем. Проверяется не «поле не пустое», а два конкретных свойства: всё
 * посчитанное доехало, и доехало **по одному разу** — числа на проводе дельта за окно, а не
 * счётчик с начала процесса, поэтому повтор означал бы удвоение потерь на сервере.
 */
class AgentLossReportingTest {
    private fun config(windowMs: Long) =
        MetrikConfig().apply {
            service = "losses"
            apiKey = "losses"
            endpoint = "127.0.0.1:1"
            systemMetrics = false
            this.windowMs = windowMs
        }

    private fun List<String>.frames() = map { MetrikJson.decodeFromString<Frame>(it) }

    @Test
    fun `an overflowing inbox should be reported in the window it happened`() =
        runBlocking {
            // Given — агента не поднимаем: очередь никто не разбирает, поэтому ровно OVERFLOW
            // замеров сверх ёмкости уходят в отказ и считаются.
            val sender = CollectingSender()
            val agent = MetrikAgent(config(windowMs = 60_000), sender, nowMs = { 0L })
            repeat(INBOX_CAPACITY + OVERFLOW) { agent.record("GET", "/x", 2, 5) }
            assertEquals(OVERFLOW, agent.counters.dropped, "счётчик агента не увидел потерь")

            // When — досылка открытого окна отправляет то, что насчитано.
            agent.stopAndFlush()

            // Then
            val frame = sender.packets.frames().first()
            assertEquals(OVERFLOW, frame.agent?.dropped, "потери не доехали до сервера")
        }

    @Test
    fun `losses should reach the wire once and not repeat in later windows`() =
        runBlocking {
            // Given — потери наживаются ДО подъёма агента, и это не удобство, а условие
            // проверки: у поднятого агента потребитель разбирает очередь раз в 200 мс, поэтому
            // случится переполнение или нет — решает планировщик, и тест бы проверял его.
            val sender = CollectingSender()
            val agent = MetrikAgent(config(windowMs = 200), sender)
            repeat(INBOX_CAPACITY + OVERFLOW) { agent.record("GET", "/x", 2, 5) }
            val counted = agent.counters.dropped
            assertEquals(OVERFLOW, counted, "потерь не случилось, проверять нечего")

            // When — поднятый агент закрывает окна и отправляет их.
            agent.start()
            withTimeout(20_000) {
                while (sender.packets.frames().sumOf { it.agent?.dropped ?: 0 } < counted) delay(10)
            }

            // Then — следующие окна те же потери не повторяют.
            val windowsSeen = agent.counters.windows
            withTimeout(20_000) {
                while (agent.counters.windows < windowsSeen + 2) delay(10)
            }
            assertEquals(
                counted,
                sender.packets.frames().sumOf { it.agent?.dropped ?: 0 },
                "потери уехали повторно: сервер удвоит их",
            )

            agent.stop()
        }
}
