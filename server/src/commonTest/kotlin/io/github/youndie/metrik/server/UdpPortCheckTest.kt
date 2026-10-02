package io.github.youndie.metrik.server

import io.github.youndie.kore.config.ConfigurationException
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Занятый UDP-порт приёма — отказ с именем переменной, а не авария процесса (#57).
 *
 * Держащий сокет биндит `0.0.0.0`, как и сервер, и порт ему выбирает система: этот класс гоняют
 * `jvmTest` и `linuxX64Test` одновременно, и зашитый порт два процесса делили бы между собой.
 *
 * Контроль «свободный порт берётся» здесь есть, в отличие от HTTP: проверка HTTP-порта биндит и
 * закрывает, и на Kotlin/Native порт освобождается позже `close()`, а UDP-сокет не закрывается —
 * его держат и с него же принимают. Датаграмма через него — `UdpReceiverTest`.
 *
 * `runBlocking`, не `runTest`: часы тестового диспетчера виртуальные, а здесь настоящий сокет.
 */
class UdpPortCheckTest {
    @Test
    fun aPortSomethingHoldsIsARefusalNamingTheVariable() {
        runBlocking {
            SelectorManager(Dispatchers.Default).use { selector ->
                aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", 0)).use { holder ->
                    val port = (holder.localAddress as InetSocketAddress).port

                    val refusal = assertFailsWith<ConfigurationException> { bindIngestPort(port) }

                    val message = refusal.message.orEmpty()
                    assertTrue("METRIK_UDP_PORT" in message, message)
                    assertTrue("$port" in message, message)
                }
            }
        }
    }

    @Test
    fun aFreePortIsBoundAndHeld() {
        val socket = bindIngestPort(0)
        try {
            assertTrue(socket.port > 0, "the system did not assign a port: ${socket.port}")

            // Держится: второй бинд на тот же порт — уже отказ.
            val second = assertFailsWith<ConfigurationException> { bindIngestPort(socket.port) }
            assertEquals("METRIK_UDP_PORT", second.problems.single().variable)
        } finally {
            socket.close()
        }
    }
}
