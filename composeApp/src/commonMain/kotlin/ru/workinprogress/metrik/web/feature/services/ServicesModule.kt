package ru.workinprogress.metrik.web.feature.services

import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import ru.workinprogress.metrik.web.feature.services.data.ServicesRepositoryImpl
import ru.workinprogress.metrik.web.feature.services.domain.GetServicesUseCase
import ru.workinprogress.metrik.web.feature.services.domain.ServicesRepository
import ru.workinprogress.metrik.web.feature.services.ui.OverviewViewModel

val servicesModule =
    module {
        singleOf(::ServicesRepositoryImpl).bind<ServicesRepository>()
        factoryOf(::GetServicesUseCase)
        viewModel { OverviewViewModel(get(), get(), get(), get()) }
    }
