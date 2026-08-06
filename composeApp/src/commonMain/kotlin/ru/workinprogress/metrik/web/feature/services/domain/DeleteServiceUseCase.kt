package ru.workinprogress.metrik.web.feature.services.domain

import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class DeleteServiceUseCase(
    private val servicesRepository: ServicesRepository,
) : UseCase<DeleteServiceUseCase.Params, Unit> {
    override suspend fun invoke(params: Params): Result<Unit> = suspendRunCatching { servicesRepository.delete(params.serviceId) }

    class Params(
        val serviceId: Long,
    )
}
