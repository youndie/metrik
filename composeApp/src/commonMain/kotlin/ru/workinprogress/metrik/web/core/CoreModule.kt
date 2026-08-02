package ru.workinprogress.metrik.web.core

import io.ktor.client.HttpClient
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import ru.workinprogress.metrik.web.core.domain.TimeSource
import ru.workinprogress.metrik.web.ui.AppShellViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Клиент строится в точке входа (baseUrl и отладочный пользователь известны только там), поэтому
 * в граф он приезжает готовым инстансом — тот же приём, что и с мостом авторизации в скилле.
 */
@OptIn(ExperimentalTime::class)
fun coreModule(httpClient: HttpClient) =
    module {
        single { httpClient }
        single<TimeSource> { TimeSource { Clock.System.now().toEpochMilliseconds() } }
        viewModel { AppShellViewModel(get(), get(), get()) }
    }
