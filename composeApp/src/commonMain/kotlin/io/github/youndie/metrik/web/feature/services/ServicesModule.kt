package io.github.youndie.metrik.web.feature.services

import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import io.github.youndie.metrik.web.feature.services.data.ServicesRepositoryImpl
import io.github.youndie.metrik.web.feature.services.domain.DeleteServiceUseCase
import io.github.youndie.metrik.web.feature.services.domain.GetServicesUseCase
import io.github.youndie.metrik.web.feature.services.domain.ServicesRepository
import io.github.youndie.metrik.web.feature.services.ui.OverviewViewModel

val servicesModule =
    module {
        singleOf(::ServicesRepositoryImpl).bind<ServicesRepository>()
        factoryOf(::GetServicesUseCase)
        factoryOf(::DeleteServiceUseCase)
        viewModel { OverviewViewModel(get(), get(), get(), get()) }
    }
