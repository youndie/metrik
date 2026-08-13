package ru.workinprogress.metrik.cli

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.ui.Box
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.posix.exit
import platform.posix.getenv
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * metrik in a terminal.
 *
 * The dashboard opens a browser page that fetches html, wasm, fonts and a fistful of API calls in
 * parallel; that fan-out is what grows the server's thread pool and, with it, its memory floor
 * (M-99). This client makes a handful of sequential calls and none of that happens.
 *
 * `runMosaic` is a suspend function, and the native linker with an explicit `entryPoint` cannot
 * find such a `main` — hence `runBlocking`.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    if (args.any { it == "-h" || it == "--help" }) {
        println(CliConfig.USAGE)
        return
    }

    val config =
        CliConfig.fromEnv { name -> getenv(name)?.toKString() }.getOrElse { failure ->
            // Configuration is explained, not stack-traced: a client that dies with an exception
            // because an environment variable is unset teaches the reader nothing.
            println(failure.message)
            println(CliConfig.USAGE)
            exit(2)
            return
        }

    runBlocking { run(config, args.firstOrNull { !it.startsWith("-") }) }
}

@OptIn(ExperimentalTime::class)
private suspend fun run(
    config: CliConfig,
    service: String?,
) {
    val client = MetrikClient(config)

    try {
        client.connect()
    } catch (failure: Throwable) {
        println("cannot reach ${config.url}/mcp: ${failure.message}")
        println("check METRIK_URL and METRIK_TOKEN")
        return
    }

    // Альтернативный буфер вокруг всего рендера: приложение занимает терминал целиком, а на выходе
    // экран возвращается таким, каким был. `finally` внутри `fullScreen` закрывает исключения,
    // `quit()` — обычный выход, обработчики сигналов — Ctrl+C.
    try {
        fullScreen { renderApp(client, config, service) }
    } finally {
        client.close()
    }
}

@OptIn(ExperimentalTime::class)
private suspend fun renderApp(
    client: MetrikClient,
    config: CliConfig,
    startService: String?,
) = runMosaic {
    var state by remember { mutableStateOf(UiState(screen = if (startService != null) Screen.DETAIL else Screen.SERVICES)) }
    var reload by remember { mutableStateOf(0) }
    var quit by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(startService) }

    val terminal = LocalTerminalState.current
    val width = terminal.size.width.takeIf { it > 20 } ?: 80
    val height = terminal.size.height.takeIf { it > 6 } ?: 24

    Box(
        modifier =
            Modifier.onKeyEvent { event ->
                when (val action = event.toAction()) {
                    null -> {
                        false
                    }

                    UiAction.Quit -> {
                        quit = true
                        true
                    }

                    UiAction.Refresh -> {
                        reload++
                        true
                    }

                    UiAction.Back -> {
                        opened = null
                        state = state.copy(screen = Screen.SERVICES, detail = null)
                        true
                    }

                    UiAction.Open -> {
                        opened = state.current?.name
                        if (opened != null) state = state.copy(screen = Screen.DETAIL, detail = null, loading = true)
                        reload++
                        true
                    }

                    UiAction.Up, UiAction.Down -> {
                        val step = if (action == UiAction.Up) -1 else 1
                        val last = (state.services.size - 1).coerceAtLeast(0)
                        state = state.copy(selected = (state.selected + step).coerceIn(0, last))
                        true
                    }
                }
            },
    ) {
        when (state.screen) {
            Screen.SERVICES -> {
                val (rows, selected) = servicesRows(state)
                Screen(rows, "↑↓ move   enter open   r refresh   q quit", config, width, height, selected)
            }

            Screen.DETAIL -> {
                Screen(
                    detailRows(state, config, width),
                    if (state.detail == null) "esc back   q quit" else "esc back   r refresh   q quit",
                    config,
                    width,
                    height,
                )
            }
        }
    }

    // One loader for both screens: which calls are made follows from the state, and the refresh
    // counter is what a keypress bumps.
    LaunchedEffect(reload, opened) {
        state = state.copy(loading = true)

        val now = Clock.System.now().toEpochMilliseconds()
        val from = now - config.windowMinutes * 60L * 1000

        state =
            runCatching {
                val services = client.services()
                val alerts = client.firingAlerts()
                val name = opened

                val detail =
                    if (name == null) {
                        null
                    } else {
                        ServiceDetail(
                            service = name,
                            overview = client.overview(name, from, now),
                            series = client.timeSeries(name, from, now),
                            slow = client.slowRoutes(name, from, now, limit = 8),
                            errors = client.serverErrors(name, from, now, limit = 5),
                            deploys = client.deploys(name, from, now),
                            from = from,
                            to = now,
                            thresholds = latencyThresholds(client.alertRules(name)),
                        )
                    }

                state.copy(
                    services = services,
                    alerts = alerts,
                    detail = detail,
                    selected = state.selected.coerceIn(0, (services.size - 1).coerceAtLeast(0)),
                    loading = false,
                    failure = null,
                    staleSince = null,
                )
            }.getOrElse { failure ->
                // The previous numbers stay on screen, labelled stale. A monitoring client that
                // blanks itself when the network blinks is worse than one that admits it.
                state.copy(
                    loading = false,
                    failure = failure.message ?: failure::class.simpleName ?: "unreachable",
                    staleSince = state.staleSince ?: now,
                )
            }
    }

    LaunchedEffect(reload) {
        delay(config.refreshSeconds.seconds)
        reload++
    }

    // Mosaic не даёт способа завершить композицию — в его же примерах выходят из процесса. Значит
    // выход обязан пройти через `quit()`, который сначала возвращает основной буфер экрана.
    LaunchedEffect(quit) {
        if (quit) quit(0)
    }
}

/**
 * Keys to intent.
 *
 * `j`/`k` alongside the arrows because the audience for a terminal monitoring client is the
 * audience that already has those under its fingers.
 */
private fun KeyEvent.toAction(): UiAction? =
    when (key) {
        "ArrowUp", "k" -> UiAction.Up
        "ArrowDown", "j" -> UiAction.Down
        "Enter" -> UiAction.Open
        "Escape" -> UiAction.Back
        "r" -> UiAction.Refresh
        "q" -> UiAction.Quit
        else -> null
    }
