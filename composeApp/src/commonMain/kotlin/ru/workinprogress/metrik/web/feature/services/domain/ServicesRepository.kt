package ru.workinprogress.metrik.web.feature.services.domain

import ru.workinprogress.metrik.api.ServiceSummary

interface ServicesRepository {
    /**
     * Список сервисов за период. `null`-период означает «живой» срез: сервер сам берёт последние
     * пять минут (см. `docs/api/endpoint-query.md`) — так спрашивает рельс, которому нужно текущее
     * состояние, а не срез за выбранный на «Обзоре» диапазон.
     */
    suspend fun services(
        from: Long? = null,
        to: Long? = null,
    ): List<ServiceSummary>
}
