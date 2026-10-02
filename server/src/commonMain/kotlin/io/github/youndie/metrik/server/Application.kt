package io.github.youndie.metrik.server

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigProblem
import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.Environment
import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.ktor.installKoreProbes
import io.github.youndie.kore.ktor.installKoreVersion
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.kore.ktor.requireListenable
import io.github.youndie.kore.ktor.startForKore
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.DrainGate
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.github.youndie.kore.mcp.KoreMcpConfig
import io.github.youndie.kore.mcp.installKoreMcp
import io.github.youndie.metrik.agent.Metrik
import io.github.youndie.metrik.agent.MetrikCountersKey
import io.github.youndie.metrik.api.Api
import io.github.youndie.metrik.server.alert.AlertNotifier
import io.github.youndie.metrik.server.alert.AlertWorker
import io.github.youndie.metrik.server.alert.NoopNotifier
import io.github.youndie.metrik.server.alert.TelegramNotifier
import io.github.youndie.metrik.server.db.migrateDb
import io.github.youndie.metrik.server.ingest.AgentStats
import io.github.youndie.metrik.server.ingest.IngestService
import io.github.youndie.metrik.server.ingest.IngestSocket
import io.github.youndie.metrik.server.ingest.UdpReceiver
import io.github.youndie.metrik.server.mcp.ToolFacade
import io.github.youndie.metrik.server.mcp.registerTools
import io.github.youndie.metrik.server.query.AdminService
import io.github.youndie.metrik.server.query.QueryService
import io.github.youndie.metrik.server.query.adminRoutes
import io.github.youndie.metrik.server.query.alertRoutes
import io.github.youndie.metrik.server.query.alertTestRoute
import io.github.youndie.metrik.server.query.queryRoutes
import io.github.youndie.metrik.server.retention.RetentionWorker
import io.github.youndie.metrik.server.web.WebAssets
import io.github.youndie.metrik.server.web.webRoutes
import io.github.youndie.metrik.wire.MetrikJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.time.Duration.Companion.seconds

/**
 * Как процесс останавливается, числами.
 *
 * 2 + 10 + 3×3 = 21 секунда внутри объявленных 30, и эти же 30 стоят в чарте
 * `terminationGracePeriodSeconds`: настоящий бюджет процессу не сообщает ни одна платформа,
 * поэтому kore его **говорят**, и чарт обязан говорить то же самое.
 *
 * Ожидание перед сливом короче пятисекундного умолчания kore, и это решение про выкаты, а не
 * замер этого кластера: metrik — одна реплика со `strategy: Recreate`, второго пода, на который
 * ушёл бы трафик, нет, так что каждая секунда здесь — секунда простоя выката и ничего больше.
 * kore мерил распространение endpoint'ов на 61 мс.
 */
private val DEADLINES =
    ShutdownDeadlines(
        preDrainWait = 2.seconds,
        drain = 10.seconds,
        releaseGroup = 3.seconds,
        gracePeriod = 30.seconds,
    )

/**
 * `SO_REUSEADDR` движка — и той же проверки порта перед стартом, одним значением на оба места.
 *
 * CIO по умолчанию ставит `false`, и на двух платформах это значит разное: JVM флаг игнорирует (JDK
 * открывает каждый серверный сокет с ним), а Kotlin/Native записывает в сокет явный ноль. Нативный
 * процесс, перезапущенный на месте, упирается в `TIME_WAIT` предшественника и не может забиндиться
 * (kore B-62). Флаг нужен **обоим** процессам, так что первый перезапуск после его включения ещё
 * может упереться. Проверка порта обязана биндить с тем же флагом, что движок: с `true` у неё и
 * `false` у движка она пропустит `TIME_WAIT`, который движок потом не возьмёт.
 */
private const val REUSE_ADDRESS = true

fun main() {
    val config = ServerConfig.fromEnv()
    // Оба порта — до миграций и до движка: занятый порт — ошибка конфигурации, и сообщить о ней
    // надо раньше, чем процесс что-нибудь изменит на диске.
    val ingestSocket = requirePorts(config)

    // Миграции накатываются здесь, до того как что-нибудь начнёт отвечать и до открытия защёлки.
    val db = openDatabase(config.dbPath, config.dbMaxConnections)
    val probes = MetrikProbes(db)
    val runtime = ServerRuntime()
    // Одна защёлка на отказ и на слив. Отказ раньше смотрел на readiness, а та падает в начале
    // announce — то есть `503` получали ровно те запросы, ради которых announce ждёт (kore B-61).
    // `EngineDrain` открывает её первым своим действием.
    val draining = DrainGate()

    val server =
        embeddedServer(
            CIO,
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = config.httpPort
                        host = "0.0.0.0"
                    },
                )
                // Движку — те же числа, которыми пользуется стадия слива. Умолчание Ktor — одна
                // секунда, а это короче очень многих настоящих запросов.
                shutdownGracePeriod = DEADLINES.drain.inWholeMilliseconds
                shutdownTimeout = (DEADLINES.drain + 5.seconds).inWholeMilliseconds
                reuseAddress = REUSE_ADDRESS
            },
            module = { module(config, db, probes, runtime, draining, ingestSocket) },
        )

    // НЕ `wait = true`. Главный поток обязан дойти до ожидания ниже, иначе сигнал приходит в
    // процесс, которому нечего исполнять: движок остановит собственный хук Ktor, а всё остальное —
    // приёмник UDP, рабочие циклы, пул — не закроет никто. Снаружи это неотличимо от чистой
    // остановки.
    //
    // И не `start(wait = false)`: на JVM он оставляет включённым собственный shutdown hook Ktor,
    // JVM исполняет хуки параллельно, и тот останавливает движок прямо по сигналу, посреди announce
    // (kore#90); на Kotlin/Native между `start` и обработчиком kore успевает встать обработчик Ktor,
    // и SIGTERM в этом окне подвешивает процесс (kore B-63). `EngineDrain` рядом с включённым хуком
    // строиться отказывается.
    server.startForKore()

    val checksScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    probes.start(checksScope)
    probes.startup.markStarted()

    runBlocking {
        runUntilSignal(
            DEADLINES,
            // Внутри колбэка, а не строкой после вызова: на JVM возврат из этой функции означает,
            // что хук завершился и рантайм уже уходит. Native продолжает работать — из-за чего
            // гоночный вариант легко написать и никогда не увидеть.
            onFinished = { run -> println(run.transcript) },
        ) {
            announce(AnnounceNotReady(probes.readiness))
            drain(EngineDrain(server, DEADLINES.drain, DEADLINES.drain + 5.seconds, draining))

            // Приёмник UDP — потребитель: он перестаёт принимать после того, как HTTP слился, а не
            // до. Рабочие циклы гасятся здесь же: им незачем начинать новый проход, когда процесс
            // уходит.
            consumer(
                participant("udp receiver and workers") {
                    runtime.receiver?.stop()
                    runtime.alerts?.stop()
                    runtime.retention?.stop()
                },
            )

            // Последним: через этот пул пишет всё, что выше. Именно этот close `ApplicationStopping`
            // исполнил бы **до** слива на Kotlin/Native и после — на JVM, из одного исходника.
            pool(participant("sqlite pool") { db.close().getOrThrow() })

            telemetry(
                participant("health checks") {
                    probes.stop()
                    checksScope.cancel()
                },
            )
        }
    }
}

/**
 * Занятый HTTP-порт — отказ старта одной строкой с именем переменной, а не `SIGABRT` (kore B-59).
 *
 * CIO биндит порт в собственной корутине уже после того, как `start` вернул управление, и на
 * Kotlin/Native ошибка бинда доходит до корня этой корутины без обработчика: процесс кончается
 * `SIGABRT` и полусотней строк стека, а переменную, которую надо поменять, не называет никто. kore
 * биндит порт один раз заранее, с тем же адресом и тем же `SO_REUSEADDR`, что и движок, и отвечает
 * `ConfigurationException`. Сужает случай, а не закрывает: порт могут занять между этой проверкой
 * и биндом движка.
 *
 * **Схема из одного ключа, и читает она не окружение процесса.** Проверка — метод `Configuration`
 * kore, а metrik читает окружение сам (`ServerConfig`). Схема kore отвергает любую необъявленную
 * переменную под своим префиксом, а под `METRIK_` в поде лежат и чужие: kubelet кладёт в окружение
 * `<SERVICE>_PORT` и `<SERVICE>_SERVICE_HOST` каждого сервиса namespace, а сервисы чарта
 * называются `<release>-metrik…` — при релизе `metrik` это `METRIK_METRIK_PORT` и соседи. Поэтому
 * схеме отдаётся только уже прочитанный порт; перевод всего конфига на схему kore — отдельная
 * задача.
 */
internal fun checkHttpPortListenable(port: Int) {
    val key = ConfigKey.int("HTTP_PORT")
    ConfigSchema("METRIK", listOf(key))
        .read(Environment.of(mapOf("METRIK_HTTP_PORT" to port.toString())))
        .requireListenable(key, reuseAddress = REUSE_ADDRESS)
}

/**
 * Занятый UDP-порт приёма — тот же отказ, что у HTTP, с именем `METRIK_UDP_PORT` (#57).
 *
 * Не проверка, а сам бинд: сокет, привязанный здесь, и принимает потом датаграммы, поэтому порт не
 * могут занять между проверкой и приёмом — см. [IngestSocket]. До этого приёмник биндил в
 * собственной корутине уже после старта движка, и на Kotlin/Native занятый порт кончал процесс
 * `Uncaught Kotlin exception` и аварийным кодом.
 */
internal fun bindIngestPort(port: Int): IngestSocket =
    try {
        IngestSocket.bind(port)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val reason = failure.message ?: failure::class.simpleName ?: "unknown failure"
        throw ConfigurationException(
            "METRIK",
            listOf(ConfigProblem("METRIK_UDP_PORT", "$port cannot be bound: $reason")),
        )
    }

/**
 * Оба порта с выходом: сообщение — и код 1, без стека поверх него.
 *
 * Оба отказа сразу, а не первый: процесс, падающий на первой ошибке, заставляет чинить их по одной,
 * перезапуском на каждую (так же kore собирает проблемы конфигурации). На успехе — привязанный
 * UDP-сокет, его забирает модуль.
 */
private fun requirePorts(config: ServerConfig): IngestSocket {
    val problems = mutableListOf<ConfigProblem>()
    try {
        checkHttpPortListenable(config.httpPort)
    } catch (refusal: ConfigurationException) {
        problems += refusal.problems
    }
    val socket =
        try {
            bindIngestPort(config.udpPort)
        } catch (refusal: ConfigurationException) {
            problems += refusal.problems
            null
        }
    if (socket != null && problems.isEmpty()) return socket
    socket?.close()
    println(ConfigurationException("METRIK", problems).message)
    endProcess(1)
}

/**
 * Ручки на то, что модуль поднял и что придётся останавливать.
 *
 * Модуль собирает приёмник и рабочие циклы сам — они нужны его же маршрутам, — а останавливать их
 * должен `main`, потому что порядок принадлежит процессу. Поэтому не возврат и не DI, а простой
 * ящик: модуль кладёт туда ручки, `main` их забирает после `start`.
 */
class ServerRuntime {
    var receiver: UdpReceiver? = null
    var alerts: AlertWorker? = null
    var retention: RetentionWorker? = null
}

/**
 * Участник из имени и лямбды.
 *
 * Параметры названы `label` и `block`, а не `name` и `stop`: внутри объекта эти два имени
 * принадлежат переопределяемым членам, и `stop()`, зовущий `stop`, был бы вызовом самого себя.
 */
private fun participant(
    label: String,
    block: suspend () -> Unit,
): ShutdownParticipant =
    object : ShutdownParticipant {
        override val name: String = label

        override suspend fun stop() {
            block()
        }
    }

/**
 * Открывает базу и накатывает миграции **до** старта движка.
 *
 * `runBlocking` здесь осознан: сервер, поднявший порт раньше готовой схемы, отвечал бы ошибками
 * на первые запросы.
 */
fun openDatabase(
    path: String,
    maxConnections: Int = 2,
): ISQLite {
    val dbPath = path.toPath()
    val fileSystem = FileSystem.SYSTEM

    if (!fileSystem.exists(dbPath)) {
        dbPath.parent?.let { parent -> if (!fileSystem.exists(parent)) fileSystem.createDirectories(parent) }
        fileSystem.write(dbPath) { }
    }

    val db =
        sqlite(
            url = "sqlite://$path",
            options =
                ConnectionPool.Options
                    .builder()
                    .maxConnections(maxConnections)
                    .build(),
        )

    runBlocking { db.migrateDb() }

    return db
}

fun Application.module(
    config: ServerConfig,
    db: ISQLite,
    probes: MetrikProbes = MetrikProbes(db),
    runtime: ServerRuntime = ServerRuntime(),
    draining: DrainGate = DrainGate(),
    // `main` биндит порт сам, до движка, и передаёт сокет сюда. Умолчание — для тестов, которые
    // поднимают модуль без `main`.
    ingestSocket: IngestSocket = bindIngestPort(config.udpPort),
) {
    // ДО маршрутов. Перехватчик, поставленный позже, пропустил бы всё, что пришло раньше него, а
    // единственный запрос, который нельзя пропустить, — первый после начала слива. Читает защёлку
    // слива, а НЕ readiness: та падает в начале announce, а announce существует, чтобы ещё
    // **отвечать**, пока новость расходится по узлам (kore B-61). Тот же экземпляр, что у
    // `EngineDrain`, иначе отказ не откроется никогда. Собственные маршруты kore не отвергаются:
    // `503` от пробы живости — это провал пробы, то есть перезапуск пода посреди остановки, о
    // которой он и сообщает.
    installShutdownRefusal(draining)
    installKoreProbes(probes.startup, probes.readiness, probes.liveness)

    // Версия и коммит, вкомпилированные плагином: у Kotlin/Native нет ни ресурсов, ни манифеста.
    installKoreVersion(KoreBuildIdentity)

    val ingest = IngestService(db, config.ingestKey)
    val receiver = UdpReceiver(ingestSocket, ingest)
    val query = QueryService(db, minuteRetentionMs = config.retentionHours * 60 * 60 * 1000)
    val admin = AdminService(db)
    val notifier: AlertNotifier =
        if (config.telegramToken.isNullOrBlank() || config.telegramChatId.isNullOrBlank()) {
            // Без токена правила всё равно считаются и видны в UI, просто молча.
            NoopNotifier
        } else {
            TelegramNotifier(config.telegramToken, config.telegramChatId)
        }
    val alerts = AlertWorker(db, admin, notifier)

    val retention = RetentionWorker(db, minuteRetentionMs = config.retentionHours * 60 * 60 * 1000)

    alerts.start(this)
    retention.start(this)
    receiver.start(this)

    // ОСТАНОВКА БОЛЬШЕ НЕ ВИСИТ НА `ApplicationStopping`, и это не перестановка строк.
    // `EmbeddedServer.stop` исполняет свои шаги в противоположном порядке на Kotlin/Native и на
    // JVM, поэтому это событие приходит **до** слива движка на той платформе, куда metrik
    // выкатывается, и после — на той, где он тестируется, из одного и того же исходника. Приёмник,
    // остановленный до слива, перестаёт принимать пакеты, пока HTTP ещё дочитывает запросы.
    //
    // Ручки уезжают в `main`: порядок принадлежит процессу, а не модулю (см. `ServerRuntime`).
    runtime.receiver = receiver
    runtime.alerts = alerts
    runtime.retention = retention

    // Dogfooding: сервер мониторинга, которого не видно, — плохой сервер мониторинга.
    // Метрики уходят в собственный UDP-порт тем же агентом, что и у чужих сервисов.
    config.selfService?.let { name ->
        install(Metrik) {
            service = name
            apiKey = config.ingestKey
            endpoint = "127.0.0.1:${config.udpPort}"
        }
    }

    // Типизированные пути: контракт объявлен в :shared и общий с дашбордом.
    install(Resources)

    install(ContentNegotiation) { json(MetrikJson) }

    // Доступ для агентов. Без METRIK_MCP_TOKEN не ставится ничего: эндпоинт живёт в обход
    // oauth2-proxy, и «забыли задать токен» не может означать «выставили наружу».
    //
    // Транспорт и охрана — kore-mcp, здесь только инструменты. Охрана висит на том узле маршрута,
    // который отвечает транспортом, поэтому «это запрос к MCP?» решает тот же роутер, что выбирает
    // обработчик; токен — только `Authorization: Bearer <token>` (docs/api/mcp-tools.md).
    //
    // Строго после `install(ContentNegotiation)`: SDK ставит свой, если не находит его, и `install`
    // приложения, окажись он ниже этой строки, падал бы с DuplicatePluginException.
    val mcpFacade = ToolFacade(query, alerts, admin)
    installKoreMcp(
        KoreMcpConfig(token = config.mcpToken, allowedHosts = config.mcpAllowedHosts),
        Implementation(name = "metrik", version = "0.1"),
    ) { registerTools(mcpFacade) }

    routing {
        // Дашборд отдаёт сам сервер — отдельного контейнера с nginx нет (M-98). Каталог
        // сканируется один раз на старте: файлы вшиты в образ и не меняются.
        // Пустая строка, а не только отсутствие переменной: образ всегда несёт статику и всегда
        // задаёт корень, так что выключить дашборд из чарта можно единственным способом — затерев
        // значение. `?.let` на пустой строке отдавал бы 404 на каждый файл вместо «дашборда нет».
        readEnv("METRIK_WEB_ROOT")?.takeIf { it.isNotBlank() }?.let { root -> webRoutes(WebAssets.scan(root)) }

        // `/health` больше не объявляется здесь: три пробы kore выше отвечают на три разных
        // вопроса, а `/health` остался их алиасом живости. Прежний маршрут пинговал базу и стоял
        // под **обеими** пробами чарта, то есть проба живости перезапускала под от недоступного
        // хранилища — а оно недоступно всем подам сразу.

        // Префикс /api несут сами ресурсы (см. `Api` в :shared), поэтому обёртки route("/api")
        // здесь нет: она бы задвоила путь.
        run {
            // Без этих счётчиков потери и отброшенные пакеты невидимы, а странные графики
            // нечем объяснить.
            get<Api.Self> {
                // Счётчики агента приезжают сюда только при самонаблюдении — иначе потери
                // на стороне отправителя не видны никому.
                val agentCounters = call.application.attributes.getOrNull(MetrikCountersKey)
                call.respond(
                    ingest.counters.snapshot().copy(
                        agent =
                            agentCounters?.let {
                                AgentStats(
                                    loops = it.loops,
                                    exited = it.exited,
                                    windows = it.windows,
                                    dropped = it.dropped,
                                    sendFailures = it.sendFailures,
                                    oversized = it.oversized,
                                )
                            },
                    ),
                )
            }

            alertRoutes(alerts)
            alertTestRoute(alerts, config)
            queryRoutes(query, config)
            adminRoutes(admin, config)
        }
    }
}
