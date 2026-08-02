package ru.workinprogress.metrik.web

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.materialkolor.Contrast
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle

/**
 * Seed-цвет темы — приглушённый стальной синий.
 *
 * metrik — инструмент наблюдения за сервисами, а не витрина: синий читается как нейтральный,
 * «информационный» цвет и не отнимает внимание у семантических сигналов состояния — красного
 * `error` (активный алерт, 5xx) и `tertiary` (менее срочные предупреждения вроде рассинхрона
 * часов). Низкая насыщенность важна и практически: дашборд держат открытым часами, и кричащая
 * палитра утомляет глаза раньше, чем на графике появится реальная проблема.
 */
private val SeedColor = Color(0xFF2F6690)

/**
 * Тема дашборда: Material 3 схема из [SeedColor] через MaterialKolor, светлая/тёмная — по
 * системной настройке (следует за ней вживую, без перезапуска приложения).
 *
 * Стиль палитры — [PaletteStyle.TonalSpot]: сдержанная, малонасыщенная схема, ближе всего к
 * дефолтной Material 3 — подходит инструменту, где данные важнее оформления. Контраст поднят на
 * ступень выше дефолта ([Contrast.Medium]): цифры и статусы должны читаться с одного взгляда,
 * а не всматриванием — по этому дашборду проверяют, не упал ли сервис.
 */
@Composable
fun MetrikTheme(content: @Composable () -> Unit) {
    DynamicMaterialTheme(
        seedColor = SeedColor,
        isDark = isSystemInDarkTheme(),
        style = PaletteStyle.TonalSpot,
        contrastLevel = Contrast.Medium.value,
        content = content,
    )
}

/**
 * Единая шкала отступов. До темы отступы расставлялись произвольными `dp` по месту — карточки,
 * таблицы и графики «плыли» друг относительно друга.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}
