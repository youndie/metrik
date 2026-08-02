package ru.workinprogress.metrik.web.feature.alerts

import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import ru.workinprogress.metrik.web.feature.alerts.data.AlertsRepositoryImpl
import ru.workinprogress.metrik.web.feature.alerts.domain.AlertsRepository
import ru.workinprogress.metrik.web.feature.alerts.domain.GetActiveAlertsUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.GetAlertHistoryUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.GetAlertRulesUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.MuteAlertRuleUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.SendTestAlertUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.UnmuteAlertRuleUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.UpdateAlertRuleUseCase
import ru.workinprogress.metrik.web.feature.alerts.ui.AlertsViewModel

val alertsModule =
    module {
        singleOf(::AlertsRepositoryImpl).bind<AlertsRepository>()
        factoryOf(::GetActiveAlertsUseCase)
        factoryOf(::GetAlertHistoryUseCase)
        factoryOf(::GetAlertRulesUseCase)
        factoryOf(::UpdateAlertRuleUseCase)
        factoryOf(::MuteAlertRuleUseCase)
        factoryOf(::UnmuteAlertRuleUseCase)
        factoryOf(::SendTestAlertUseCase)
        viewModel { AlertsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    }
