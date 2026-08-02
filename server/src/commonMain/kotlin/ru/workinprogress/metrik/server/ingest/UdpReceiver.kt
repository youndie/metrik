package ru.workinprogress.metrik.server.ingest

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import ru.workinprogress.metrik.wire.MAX_PACKET_BYTES

/**
 * Слушает UDP и отдаёт содержимое датаграмм в [IngestService].
 *
 * Ответов нет: агент ничего не ждёт. Любая ошибка на одном пакете считается и не мешает следующим —
 * приёмник, падающий от одного кривого пакета, бесполезен.
 *
 * Порт живёт **внутри кластера**: UDP не аутентифицируется, ключ в пакете отсекает случайное,
 * а не злонамеренное (docs/research/research-architecture.md §Р7).
 */
@OptIn(DelicateCoroutinesApi::class)
class UdpReceiver(
    private val port: Int,
    private val ingest: IngestService,
    private val host: String = "0.0.0.0",
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.Default) { listen() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun listen() {
        // Собственный поток под select()-цикл, а не воркер Dispatchers.Default.
        // Иначе селектор занимает воркер намертво: на машине с двумя ядрами двух селекторов
        // достаточно, чтобы в пуле не осталось никого, кто возобновит корутину по таймеру —
        // delay перестаёт срабатывать во всём процессе. Для агента это вдвойне неприемлемо:
        // он обязан не влиять на сервис, в который встроен.
        val selector = SelectorManager(newSingleThreadContext("metrik-ingest-udp"))
        val socket =
            aSocket(selector).udp().bind(
                io.ktor.network.sockets
                    .InetSocketAddress(host, port),
            )

        try {
            while (currentlyActive()) {
                val datagram = socket.receive()

                try {
                    // Пакеты больше бюджета агент не шлёт, но чужой отправитель может: читаем не
                    // больше лимита, чтобы кривой источник не съел память.
                    val payload = datagram.packet.readText(max = MAX_PACKET_BYTES * 4)
                    ingest.accept(payload)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (_: Throwable) {
                    ingest.counters.recordFailure()
                }
            }
        } finally {
            socket.close()
            selector.close()
        }
    }

    private suspend fun currentlyActive(): Boolean = kotlin.coroutines.coroutineContext.isActive
}
