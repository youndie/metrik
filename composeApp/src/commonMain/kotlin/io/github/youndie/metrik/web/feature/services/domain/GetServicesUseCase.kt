package io.github.youndie.metrik.web.feature.services.domain

import io.github.youndie.metrik.api.ServiceSummary
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

class GetServicesUseCase(
    private val servicesRepository: ServicesRepository,
) : UseCase<GetServicesUseCase.Params, List<ServiceSummary>> {
    override suspend fun invoke(params: Params): Result<List<ServiceSummary>> =
        suspendRunCatching { servicesRepository.services(params.from, params.to) }

    class Params(
        val from: Long? = null,
        val to: Long? = null,
    )
}
