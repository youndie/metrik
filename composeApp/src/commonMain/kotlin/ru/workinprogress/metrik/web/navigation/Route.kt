package ru.workinprogress.metrik.web.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Все экраны дашборда одной запечатанной иерархией.
 *
 * Состояние навигации — это стек, а не переменная: `var route by mutableStateOf(...)` не даёт ни
 * истории, ни кнопки «назад», ни адресной строки. Маршруты обязаны быть `@Serializable` — на wasm
 * рефлексии для восстановления стека нет.
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Overview : Route

    @Serializable
    data object Alerts : Route

    /** Список сервисов на весь экран — мобильная замена списку в рельсе. */
    @Serializable
    data object Services : Route

    /**
     * Сервис открывается по id, а не по объекту сводки: в адресной строке и в восстановленном
     * стеке живут только идентификаторы, а свежие данные экран берёт сам.
     */
    @Serializable
    data class Service(
        val id: Long,
    ) : Route
}

/** Регистрирует подтипы [Route], чтобы `rememberNavBackStack` мог сериализовать `List<NavKey>`. */
val routeSavedStateConfig: SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(Route.Overview::class, Route.Overview.serializer())
                    subclass(Route.Alerts::class, Route.Alerts.serializer())
                    subclass(Route.Services::class, Route.Services.serializer())
                    subclass(Route.Service::class, Route.Service.serializer())
                }
            }
    }
