package ru.workinprogress.metrik.server.alert

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

/**
 * Curl, а не CIO: TLS на нативе умеет только он (из доступных под все наши таргеты). Требует
 * `libcurl` — в рантайм-образе это пакет `libcurl4`, на сборочной машине заголовки.
 */
internal actual fun notifierHttpClient(): HttpClient = HttpClient(Curl)
