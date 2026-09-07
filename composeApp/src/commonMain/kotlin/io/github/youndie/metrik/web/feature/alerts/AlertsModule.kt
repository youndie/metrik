package io.github.youndie.metrik.web.feature.alerts

import io.github.youndie.metrik.web.feature.alerts.data.AlertsRepositoryImpl
import io.github.youndie.metrik.web.feature.alerts.domain.AlertsRepository
import io.github.youndie.metrik.web.feature.alerts.domain.GetActiveAlertsUseCase
import io.github.youndie.metrik.web.feature.alerts.domain.GetAlertHistoryUseCase
import io.github.youndie.metrik.web.feature.alerts.domain.GetAlertRulesUseCase
import io.github.youndie.metrik.web.feature.alerts.domain.MuteAlertRuleUseCase
import io.github.youndie.metrik.web.feature.alerts.domain.SendTestAlertUseCase
import io.github.youndie.metrik.web.feature.alerts.domain.UnmuteAlertRuleUseCase
import io.github.youndie.metrik.web.feature.alerts.domain.UpdateAlertRuleUseCase
import io.github.youndie.metrik.web.feature.alerts.ui.AlertsViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

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
