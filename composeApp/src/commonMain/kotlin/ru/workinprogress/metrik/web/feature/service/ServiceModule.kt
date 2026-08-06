package ru.workinprogress.metrik.web.feature.service

import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import ru.workinprogress.metrik.web.feature.service.data.ServiceMetricsRepositoryImpl
import ru.workinprogress.metrik.web.feature.service.domain.GetRoutesUseCase
import ru.workinprogress.metrik.web.feature.service.domain.GetSlowRequestsUseCase
import ru.workinprogress.metrik.web.feature.service.domain.GetSystemPointsUseCase
import ru.workinprogress.metrik.web.feature.service.domain.GetTimeSeriesUseCase
import ru.workinprogress.metrik.web.feature.service.domain.ServiceMetricsRepository
import ru.workinprogress.metrik.web.feature.service.ui.ServiceViewModel

val serviceModule =
    module {
        singleOf(::ServiceMetricsRepositoryImpl).bind<ServiceMetricsRepository>()
        factoryOf(::GetTimeSeriesUseCase)
        factoryOf(::GetRoutesUseCase)
        factoryOf(::GetSlowRequestsUseCase)
        factoryOf(::GetSystemPointsUseCase)
        viewModel { (serviceId: Long) -> ServiceViewModel(serviceId, get(), get(), get(), get(), get(), get(), get()) }
    }
