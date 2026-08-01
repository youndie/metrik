package ru.workinprogress.metrik.web

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Отладочный запуск дашборда: `./gradlew :composeApp:run`.
 * Прод — wasm-бандл, см. `docs/services/metrik-web.md`.
 */
fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = "metrik") {
            App()
        }
    }
