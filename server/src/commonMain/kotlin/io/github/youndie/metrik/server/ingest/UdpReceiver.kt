package io.github.youndie.metrik.server.ingest

import io.github.youndie.metrik.wire.MAX_PACKET_BYTES
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking

/**
 * UDP-порт приёма, **уже** привязанный.
 *
 * Привязка отделена от приёма, потому что у них разное время. Приёмник запускается в модуле, то
 * есть уже внутри старта движка, и раньше биндил там же — в собственной корутине. Ошибка такого
 * бинда доходила до корня корутины без обработчика, и на Kotlin/Native занятый порт кончал процесс
 * `Uncaught Kotlin exception: …AddressAlreadyInUseException` и аварийным кодом, не назвав
 * переменную, которую оператору менять (#57). Теперь `main` биндит порт **до** движка, отказ
 * становится отказом конфигурации, а приёмнику достаётся готовый сокет.
 *
 * Проверкой порта, как у HTTP (`requireListenable` kore), это быть не могло и не должно. Проверка
 * биндит и закрывает, а движок биндит снова: между ними порт могут занять, и на Kotlin/Native
 * `close()` сокета `ktor-network` отдаёт дескриптор потоку селектора, так что порт освобождается
 * позже — проверка сама вызывает отказ, от которого защищает. У HTTP другого пути нет, сокет
 * открывает CIO. UDP-сокет открываем мы, поэтому его можно не проверять, а **держать**: гонки нет
 * вовсе.
 */
@OptIn(DelicateCoroutinesApi::class)
class IngestSocket private constructor(
    private val selector: SelectorManager,
    internal val socket: BoundDatagramSocket,
) {
    /** Порт, который сокет держит на самом деле: при запрошенном `0` его выбрала система. */
    val port: Int get() = (socket.localAddress as InetSocketAddress).port

    fun close() {
        socket.close()
        selector.close()
    }

    companion object {
        /**
         * Биндит [host]:[port] сейчас, до возврата. Отказ бинда — исключение `ktor-network` как есть
         * (`AddressAlreadyInUseException` на Kotlin/Native, `BindException` на JVM); имя переменной
         * к нему приставляет вызывающий, он его знает.
         *
         * `SO_REUSEADDR` не ставится, и это не забыто: у UDP нет `TIME_WAIT`, ради которого флаг
         * стоит на HTTP-движке, а на Linux UDP-сокеты, поставившие его оба, биндят один порт
         * вместе — второй metrik молча делил бы приём с первым, хотя это ровно тот случай, который
         * здесь должен быть отказом.
         */
        fun bind(
            port: Int,
            host: String = "0.0.0.0",
        ): IngestSocket {
            // Собственный поток под select()-цикл, а не воркер Dispatchers.Default.
            // Иначе селектор занимает воркер намертво: на машине с двумя ядрами двух селекторов
            // достаточно, чтобы в пуле не осталось никого, кто возобновит корутину по таймеру —
            // delay перестаёт срабатывать во всём процессе. Для агента это вдвойне неприемлемо:
            // он обязан не влиять на сервис, в который встроен.
            val selector = SelectorManager(newSingleThreadContext("metrik-ingest-udp"))
            val socket =
                try {
                    runBlocking { aSocket(selector).udp().bind(InetSocketAddress(host, port)) }
                } catch (failure: Throwable) {
                    selector.close()
                    throw failure
                }
            return IngestSocket(selector, socket)
        }
    }
}

/**
 * Слушает UDP и отдаёт содержимое датаграмм в [IngestService].
 *
 * Ответов нет: агент ничего не ждёт. Любая ошибка на одном пакете считается и не мешает следующим —
 * приёмник, падающий от одного кривого пакета, бесполезен.
 *
 * Сокет приходит привязанным ([IngestSocket]) и закрывается здесь же, когда приём кончается.
 *
 * Порт живёт **внутри кластера**: UDP не аутентифицируется, ключ в пакете отсекает случайное,
 * а не злонамеренное (docs/research/research-architecture.md §Р7).
 */
class UdpReceiver(
    private val endpoint: IngestSocket,
    private val ingest: IngestService,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.Default) { listen() }
    }

    /**
     * Останавливает цикл и ЖДЁТ проход, который уже идёт. `cancel()` без `join` возвращался, пока
     * проход ещё был в запросе к базе, и тот доезжал до стадии, закрывающей пул.
     */
    suspend fun stop() {
        val running = job
        job = null
        running?.cancelAndJoin()
    }

    @Suppress(
        "ktlint:kapkan:swallowed-failure",
        "негодная датаграмма считается в счётчик отказов и не роняет цикл приёма",
    )
    private suspend fun listen() {
        try {
            while (currentlyActive()) {
                val datagram = endpoint.socket.receive()

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
            endpoint.close()
        }
    }

    private suspend fun currentlyActive(): Boolean = kotlin.coroutines.coroutineContext.isActive
}
