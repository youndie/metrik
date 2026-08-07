package ru.workinprogress.metrik.server.alert

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

internal actual fun notifierHttpClient(): HttpClient = HttpClient(CIO)
