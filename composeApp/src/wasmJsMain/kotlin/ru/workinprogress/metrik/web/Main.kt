package ru.workinprogress.metrik.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTime::class)
fun main() {
    ComposeViewport {
        // Базовый URL пустой: дашборд и API стоят за одним хостом, разводка по путям на ingress.
        App(nowMs = { Clock.System.now().toEpochMilliseconds() })
    }
}
