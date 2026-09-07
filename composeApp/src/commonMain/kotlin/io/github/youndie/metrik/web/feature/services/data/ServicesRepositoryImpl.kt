package io.github.youndie.metrik.web.feature.services.data

import io.github.youndie.metrik.api.Api
import io.github.youndie.metrik.api.ServiceSummary
import io.github.youndie.metrik.web.feature.services.domain.ServicesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.get

class ServicesRepositoryImpl(
    private val client: HttpClient,
) : ServicesRepository {
    override suspend fun services(
        from: Long?,
        to: Long?,
    ): List<ServiceSummary> = client.get(Api.Services(from = from, to = to)).body()

    override suspend fun delete(serviceId: Long) {
        client.delete(Api.Admin.Service(id = serviceId))
    }
}
