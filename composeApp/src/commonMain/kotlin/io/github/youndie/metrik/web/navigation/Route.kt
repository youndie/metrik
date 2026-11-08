package io.github.youndie.metrik.web.navigation

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

/**
 * Имя маршрута в адресной строке и его параметры — одно место на обе стороны: историю браузера
 * (`BrowserBackStackSync`) и ссылки, которые меню правой кнопки копирует и открывает в новой
 * вкладке (M-122). Разойдясь, ссылка «Copy link» вела бы на «Обзор».
 */
val Route.historyName: String
    get() =
        when (this) {
            Route.Overview -> "overview"
            Route.Alerts -> "alerts"
            Route.Services -> "services"
            is Route.Service -> "service"
        }

val Route.historyParameters: Map<String, String>
    get() =
        when (this) {
            is Route.Service -> mapOf("id" to id.toString())
            else -> emptyMap()
        }

/**
 * Фрагмент адреса маршрута — `#service?id=3`, в том виде, в каком его пишет в адресную строку
 * `navigation3-browser` (`buildBrowserHistoryFragment`).
 */
fun Route.fragment(): String =
    "#" + historyName +
        historyParameters.entries
            .joinToString("&") { (key, value) -> "$key=$value" }
            .let { if (it.isEmpty()) "" else "?$it" }

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
