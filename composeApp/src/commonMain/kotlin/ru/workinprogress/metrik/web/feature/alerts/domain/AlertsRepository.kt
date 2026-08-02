package ru.workinprogress.metrik.web.feature.alerts.domain

import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.api.AlertView

interface AlertsRepository {
    /** Горящие сейчас (на сервере это `WHERE state = 'FIRING'`). */
    suspend fun active(): List<AlertView>

    /** Записанные переходы FIRING/OK, последние 100 — это и есть «история», а не срез активных. */
    suspend fun history(): List<AlertView>

    /**
     * Пороги правил сервиса. Админский эндпоинт: без `METRIK_ADMINS` доступен всем, кто прошёл
     * прокси, иначе кто угодно вне списка получит 403 — вызывающий обязан показать это как «нет
     * доступа», а не как «нет данных».
     */
    suspend fun rules(serviceId: Long): List<AlertRuleView>

    /** Сервер возвращает актуальный список правил, поэтому перезапрос после мутации не нужен. */
    suspend fun updateRule(
        serviceId: Long,
        rule: AlertRuleView,
    ): List<AlertRuleView>

    /**
     * Заглушает **доставку** на [minutes] (M-81) — правило продолжает считаться и гореть в UI
     * (`AlertRuleView.mutedUntil`, `AlertView.mutedUntil`).
     */
    suspend fun mute(
        serviceId: Long,
        ruleId: String,
        minutes: Long,
    ): List<AlertRuleView>

    suspend fun unmute(
        serviceId: Long,
        ruleId: String,
    ): List<AlertRuleView>

    /**
     * Тестовое уведомление (M-81) — единственный способ узнать, что доставка настроена, не
     * дожидаясь настоящей аварии. `false` означает «Telegram не настроен или недоступен», и
     * показывать это надо как есть.
     */
    suspend fun sendTest(): Boolean
}
