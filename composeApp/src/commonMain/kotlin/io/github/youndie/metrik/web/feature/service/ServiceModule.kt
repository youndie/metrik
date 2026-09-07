package io.github.youndie.metrik.web.feature.service

import io.github.youndie.metrik.web.feature.service.data.ServiceMetricsRepositoryImpl
import io.github.youndie.metrik.web.feature.service.domain.GetRoutesUseCase
import io.github.youndie.metrik.web.feature.service.domain.GetSlowRequestsUseCase
import io.github.youndie.metrik.web.feature.service.domain.GetSystemPointsUseCase
import io.github.youndie.metrik.web.feature.service.domain.GetTimeSeriesUseCase
import io.github.youndie.metrik.web.feature.service.domain.ServiceMetricsRepository
import io.github.youndie.metrik.web.feature.service.ui.ServiceViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val serviceModule =
    module {
        singleOf(::ServiceMetricsRepositoryImpl).bind<ServiceMetricsRepository>()
        factoryOf(::GetTimeSeriesUseCase)
        factoryOf(::GetRoutesUseCase)
        factoryOf(::GetSlowRequestsUseCase)
        factoryOf(::GetSystemPointsUseCase)
        viewModel { ServiceViewModel(get(), get(), get(), get(), get(), get(), get()) }
    }
