package ru.workinprogress.metrik.agent

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.workinprogress.metrik.wire.Frame
import ru.workinprogress.metrik.wire.MetrikJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Настоящая отправка настоящим сокетом.
 *
 * До этого теста `UdpSender` не проверялся ни разу: [MetrikPluginTest] подставляет фейковый
 * `MetrikSender`, а агент по контракту глотает любые ошибки отправки — снаружи сломанный
 * отправитель неотличим от исправного. Именно так «агент на native молчит» и прожило до прода:
 * тесты были зелёные, потому что проверяли всё, кроме сокета.
 *
 * Тест общий, а не jvm-only, намеренно: ломается отправка именно на нативных таргетах, и ловить
 * это обязан тот же тест, что гоняется на JVM.
 */
class UdpSenderTest {
    private val port = 19_317

    /**
     * runBlocking, а не runTest: тест ждёт настоящий сокет и настоящее время. В runTest время
     * виртуальное, delay пропускается мгновенно, и withTimeout «истекает» раньше, чем датаграмма
     * успевает долететь.
     */
    @Test
    fun `a packet should actually leave the socket and arrive`() =
        runBlocking {
            // Given — слушатель на локальном порту.
            val selector = SelectorManager(newSingleThreadContext("udp-test-listener"))
            val listener: BoundDatagramSocket = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", port))
            val scope = CoroutineScope(coroutineContext + Job())
            val received: Deferred<String> =
                scope.async {
                    listener
                        .receive()
                        .packet
                        .readText()
                }
            delay(200)

            // When
            val sender = UdpSender("127.0.0.1:$port")
            sender.send("""{"hello":"metrik"}""")

            // Then
            val payload = withTimeout(10_000) { received.await() }
            assertEquals("""{"hello":"metrik"}""", payload)

            sender.close()
            listener.close()
            selector.close()
        }

    /**
     * Сквозная проверка: агент целиком, с настоящим отправителем, должен доставить окно.
     *
     * Проверяются и счётчики самого агента — они единственное, чем «цикл окон не крутится»
     * отличается от «отправка падает»: снаружи оба выглядят как молчание.
     */
    @Test
    fun `the agent should deliver a window through a real socket`() =
        runBlocking {
            // Given
            val selector = SelectorManager(newSingleThreadContext("udp-test-listener-2"))
            val listener = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", port + 1))
            val scope = CoroutineScope(coroutineContext + Job())
            val received: Deferred<String> =
                scope.async {
                    listener
                        .receive()
                        .packet
                        .readText()
                }
            delay(200)

            val config =
                MetrikConfig().apply {
                    service = "native-probe"
                    apiKey = "probe-key"
                    endpoint = "127.0.0.1:${port + 1}"
                    instanceId = "probe-instance"
                    windowMs = 200
                    systemMetrics = false
                }
            val agent = MetrikAgent(config, UdpSender(config.endpoint))
            agent.start(scope)
            agent.record("GET", "/probe/{id}", 200, 12)

            // When
            val payload = withTimeout(20_000) { received.await() }

            // Then — долетел разбираемый кадр именно этого сервиса.
            val frame = MetrikJson.decodeFromString<Frame>(payload)
            assertEquals("native-probe", frame.service)
            assertEquals("probe-instance", frame.instance)

            // И счётчики агента подтверждают, что молчания не было ни на одном шаге.
            assertTrue(agent.counters.loops > 0, "цикл окон не получил выполнения")
            assertTrue(agent.counters.windows > 0, "окно ни разу не закрылось")
            assertEquals(0, agent.counters.sendFailures, "отправка падала")
            assertEquals(0, agent.counters.exited, "цикл окон вышел досрочно")

            agent.stop()
            listener.close()
            selector.close()
        }

    /**
     * Тот же путь, но адрес задан **именем**, а не цифрами.
     *
     * В проде endpoint всегда доменный (`metrik-metrik-ingest.metrik.svc.cluster.local:9999`), а
     * резолвинг имени на нативных таргетах — отдельный механизм, не тот, что разбирает `127.0.0.1`.
     * Проверка по цифровому адресу его не задевает вовсе, поэтому она и не поймала бы поломку,
     * из-за которой агент молчал в кластере.
     */
    @Test
    fun `a hostname endpoint should resolve and deliver`() =
        runBlocking {
            // Given
            val selector = SelectorManager(newSingleThreadContext("udp-test-listener-3"))
            val listener = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", port + 2))
            val scope = CoroutineScope(coroutineContext + Job())
            val received: Deferred<String> =
                scope.async {
                    listener
                        .receive()
                        .packet
                        .readText()
                }
            delay(200)

            // When — имя, а не адрес.
            val sender = UdpSender("localhost:${port + 2}")
            sender.send("""{"hello":"by-name"}""")

            // Then
            assertEquals("""{"hello":"by-name"}""", withTimeout(10_000) { received.await() })

            sender.close()
            listener.close()
            selector.close()
        }
}
