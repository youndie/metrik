package ru.workinprogress.metrik.web.feature.services.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import ru.workinprogress.metrik.api.Api
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.web.feature.services.domain.ServicesRepository

class ServicesRepositoryImpl(
    private val client: HttpClient,
) : ServicesRepository {
    override suspend fun services(
        from: Long?,
        to: Long?,
    ): List<ServiceSummary> = client.get(Api.Services(from = from, to = to)).body()
}
