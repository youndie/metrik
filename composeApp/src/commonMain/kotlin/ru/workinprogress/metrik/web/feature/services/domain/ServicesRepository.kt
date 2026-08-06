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

    /**
     * Убирает сервис из наблюдения вместе со всеми его данными.
     *
     * Нужно ровно тогда, когда сервис переименовали или увели: старая запись продолжает жить и
     * «гореть» правилом `absent`, потому что честно молчит. Заглушение тут не подходит — оно
     * прячет живую проблему, а здесь проблемы нет, есть мусор.
     */
    suspend fun delete(serviceId: Long)
}
