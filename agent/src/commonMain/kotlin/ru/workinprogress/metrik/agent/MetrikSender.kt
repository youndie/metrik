package ru.workinprogress.metrik.agent

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ConnectedDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.io.Buffer
import ru.workinprogress.metrik.wire.DEFAULT_INGEST_PORT

/** Куда агент отправляет пакеты. Отдельный интерфейс — чтобы тесты не открывали сокет. */
interface MetrikSender {
    suspend fun send(packet: String)

    fun close()
}

/**
 * UDP-отправка. Fire-and-forget: ответа нет, ретраев нет, ожидания нет.
 *
 * Сокет пересоздаётся после ошибки — контейнер с metrik мог переехать, и DNS-имя резолвится заново.
 * Любая ошибка проглатывается и считается вызывающим: молчание metrik не должно превращаться
 * в проблему целевого сервиса.
 */
class UdpSender(
    endpoint: String,
) : MetrikSender {
    private val host: String = endpoint.substringBeforeLast(':')
    private val port: Int = endpoint.substringAfterLast(':', "").toIntOrNull() ?: DEFAULT_INGEST_PORT

    // SelectorManager() без аргументов сам берёт подходящий диспетчер под платформу:
    // Dispatchers.IO на нативе internal и в commonMain недоступен.
    private val selector = SelectorManager()
    private var socket: ConnectedDatagramSocket? = null

    override suspend fun send(packet: String) {
        val bytes = packet.encodeToByteArray()

        try {
            val active = socket ?: connect().also { socket = it }
            active.send(Datagram(Buffer().also { it.write(bytes) }, active.remoteAddress))
        } catch (cause: Throwable) {
            closeSocket()
            throw cause
        }
    }

    override fun close() {
        closeSocket()
        runCatching { selector.close() }
    }

    private suspend fun connect(): ConnectedDatagramSocket = aSocket(selector).udp().connect(InetSocketAddress(host, port))

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }
}
