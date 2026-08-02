package ru.workinprogress.metrik.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        // Базовый URL пустой: дашборд и API стоят за одним хостом, разводка по путям на ingress.
        App()
    }
}
