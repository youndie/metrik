package io.github.youndie.metrik.server

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.ktor.installKoreProbes
import io.github.youndie.kore.ktor.installKoreVersion
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
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
import io.github.youndie.metrik.server.ingest.UdpReceiver
import io.github.youndie.metrik.server.mcp.ToolFacade
import io.github.youndie.metrik.server.mcp.installMcp
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

fun main() {
    val config = ServerConfig.fromEnv()

    // Миграции накатываются здесь, до того как что-нибудь начнёт отвечать и до открытия защёлки.
    val db = openDatabase(config.dbPath, config.dbMaxConnections)
    val probes = MetrikProbes(db)
    val runtime = ServerRuntime()

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
            },
            module = { module(config, db, probes, runtime) },
        )

    // НЕ `wait = true`. Главный поток обязан дойти до ожидания ниже, иначе сигнал приходит в
    // процесс, которому нечего исполнять: движок остановит собственный хук Ktor, а всё остальное —
    // приёмник UDP, рабочие циклы, пул — не закроет никто. Снаружи это неотличимо от чистой
    // остановки.
    server.start(wait = false)

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
            drain(EngineDrain(server, DEADLINES.drain, DEADLINES.drain + 5.seconds))

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
) {
    // ДО маршрутов. Перехватчик, поставленный позже, пропустил бы всё, что пришло раньше него, а
    // единственный запрос, который нельзя пропустить, — первый после того, как readiness ушла в
    // false. Собственные маршруты kore не отвергаются: `503` от пробы живости — это провал пробы,
    // то есть перезапуск пода посреди остановки, о которой он и сообщает.
    installShutdownRefusal(isShuttingDown = { probes.readiness.isShuttingDown })
    installKoreProbes(probes.startup, probes.readiness, probes.liveness)

    // Версия и коммит, вкомпилированные плагином: у Kotlin/Native нет ни ресурсов, ни манифеста.
    installKoreVersion(KoreBuildIdentity)

    val ingest = IngestService(db, config.ingestKey)
    val receiver = UdpReceiver(config.udpPort, ingest)
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
    // Строго после ContentNegotiation: `mcpStatelessStreamableHttp` ставит его сам, если тот ещё
    // не стоит, и приложение падало с DuplicatePluginException. Найдя плагин на месте, SDK
    // ограничивается предупреждением.
    installMcp(config, ToolFacade(query, alerts, admin))

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
