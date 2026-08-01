package ru.workinprogress.metrik.agent

import io.ktor.server.application.createApplicationPlugin
import ru.workinprogress.metrik.wire.DEFAULT_WINDOW_MS

/**
 * Конфигурация агента. Поля и дефолты — `docs/services/metrik-agent.md`.
 */
class MetrikConfig {
    /** Логическое имя сервиса. Регистрации нет: имя и есть идентификатор, опечатка заведёт фантом. */
    var service: String = ""

    /** Ingest-key инсталляции metrik (один на установку, не на сервис). */
    var apiKey: String = ""

    /** `host:port` metrik-server. */
    var endpoint: String = ""

    /** Версия релиза; смена значения рисует отметку деплоя на графиках. */
    var release: String? = null

    var windowMs: Long = DEFAULT_WINDOW_MS
    var maxSeries: Int = 200
    var slowSamples: Int = 5
    var systemMetrics: Boolean = true
    var enabled: Boolean = true
}

/**
 * Скелет плагина: замер, агрегация и отправка появляются в M2 (`M-20`…`M-24`).
 *
 * Главный инвариант, который нельзя потерять при наполнении: отказ metrik не должен влиять на
 * целевой сервис — ни одно исключение агента не всплывает в pipeline запроса.
 */
val Metrik =
    createApplicationPlugin(name = "Metrik", createConfiguration = ::MetrikConfig) {
        // M-20: хуки Metrics / ResponseSent / CallFailed
    }
