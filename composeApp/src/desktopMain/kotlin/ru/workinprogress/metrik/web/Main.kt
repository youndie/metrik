package ru.workinprogress.metrik.web

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ru.workinprogress.metrik.web.core.data.metrikHttpClient

/**
 * Отладочный запуск: `./gradlew :composeApp:run`.
 *
 * Адрес сервера берётся из `METRIK_URL` — при разработке дашборд и API живут на разных портах,
 * в проде оба за одним хостом и базовый URL пустой.
 */
fun main() {
    val baseUrl = System.getenv("METRIK_URL") ?: "http://127.0.0.1:8080"
    val user = System.getenv("METRIK_USER") ?: "local"

    application {
        Window(onCloseRequest = ::exitApplication, title = "metrik") {
            App(metrikHttpClient(baseUrl, user))
        }
    }
}
