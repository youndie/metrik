package io.github.youndie.metrik.server

import io.github.youndie.kore.config.ConfigurationException
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Занятый HTTP-порт — отказ с именем переменной, а не `SIGABRT` движка (kore B-59).
 *
 * Сама проверка бинда — kore, и тестируется там. Здесь то, чего kore за metrik проверить не может:
 * что `main` спрашивает его о том порте и называет ту переменную, которую оператору менять.
 *
 * Держащий сокет слушает `0.0.0.0`, как и движок: на macOS `SO_REUSEADDR` пускает бинд на
 * `0.0.0.0`, когда занят только `127.0.0.1`, и тест на петлевом адресе там прошёл бы мимо.
 *
 * Контроля «свободный порт проходит» нет намеренно: на Kotlin/Native `close()` серверного сокета
 * `ktor-network` отдаёт дескриптор потоку селектора и порт освобождается позже, так что такой
 * контроль был бы флаки (kore ловил это в собственном CI). Каждый старт сервера в образе — тот
 * самый контроль: проверка стоит в `main` перед движком.
 *
 * `runBlocking`, не `runTest`: часы тестового диспетчера виртуальные, а здесь настоящий сокет.
 */
class HttpPortCheckTest {
    @Test
    fun aPortSomethingListensOnIsARefusalNamingTheVariable() {
        runBlocking {
            SelectorManager(Dispatchers.Default).use { selector ->
                aSocket(selector).tcp().bind(InetSocketAddress("0.0.0.0", 0)).use { holder ->
                    val port = (holder.localAddress as InetSocketAddress).port

                    val refusal = assertFailsWith<ConfigurationException> { checkHttpPortListenable(port) }

                    val message = refusal.message.orEmpty()
                    assertTrue("METRIK_HTTP_PORT" in message, message)
                    assertTrue("$port" in message, message)
                }
            }
        }
    }
}
