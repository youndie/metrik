package ru.workinprogress.metrik.web

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.materialkolor.Contrast
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle

/**
 * Seed-цвет светлой темы — приглушённый стальной синий.
 *
 * metrik — инструмент наблюдения за сервисами, а не витрина: синий читается как нейтральный,
 * «информационный» цвет и не отнимает внимание у семантических сигналов состояния — красного
 * `error` (активный алерт, 5xx) и `tertiary` (менее срочные предупреждения вроде рассинхрона
 * часов). Низкая насыщенность важна и практически: дашборд держат открытым часами, и кричащая
 * палитра утомляет глаза раньше, чем на графике появится реальная проблема.
 */
private val SeedColor = Color(0xFF2F6690)

/**
 * Тёмная схема — точные цвета макета `docs/design/metrik-expressive.html`, а не сгенерированные
 * MaterialKolor тона: макет явно фиксирует hex-значения ролей, и подгонять их под seed-цвет
 * означало бы разойтись со спецификацией без причины.
 *
 * Часть ролей (`onSecondary`, `onTertiary`, `onError`, `inverse*`, `*Fixed*`, `surfaceBright`,
 * `surfaceContainerHighest`) макет не описывает явно — они достроены по контрасту в духе
 * остальной палитры, т.к. Material 3 требует их для полноты схемы, а в разметке им пары нет.
 */
private val MetrikDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF9ECAFF),
        onPrimary = Color(0xFF00325A),
        primaryContainer = Color(0xFF004A7C),
        onPrimaryContainer = Color(0xFFD3E4FF),
        secondary = Color(0xFFB9C2CC),
        onSecondary = Color(0xFF1B2530),
        secondaryContainer = Color(0xFF35434F),
        onSecondaryContainer = Color(0xFFD6E3F0),
        tertiary = Color(0xFFD9BDE8),
        onTertiary = Color(0xFF3B2948),
        tertiaryContainer = Color(0xFF4E3E5A),
        onTertiaryContainer = Color(0xFFF1DAFF),
        background = Color(0xFF07090B),
        onBackground = Color(0xFFE3E6EB),
        surface = Color(0xFF0F1418),
        onSurface = Color(0xFFE3E6EB),
        surfaceVariant = Color(0xFF242A2F),
        onSurfaceVariant = Color(0xFFB9C2CC),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF8C0009),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF6E7780),
        outlineVariant = Color(0xFF242A2F),
        surfaceDim = Color(0xFF0A0E12),
        surfaceBright = Color(0xFF2C333A),
        surfaceContainerLowest = Color(0xFF07090B),
        surfaceContainerLow = Color(0xFF161B1F),
        surfaceContainer = Color(0xFF1A1F24),
        surfaceContainerHigh = Color(0xFF242A2F),
        surfaceContainerHighest = Color(0xFF2C333A),
    )

/**
 * Акценты, которых нет в ролях Material 3 (например «свежесть» — зелёный статус здорового
 * сервиса). Макет описывает только тёмную тему — в светлой эти значения используются как есть:
 * это цвета смысла (здоров/критично/нейтрально), а не тона поверхности, подгонять их под
 * сгенерированную MaterialKolor-схему незачем.
 */
object MetrikExtra {
    val dim = Color(0xFF8A939D)
    val healthy = Color(0xFF7BD69C)
    val healthyContainer = Color(0xFF1E4B33)
    val onHealthyContainer = Color(0xFFA6F0C0)
    val neutralDot = Color(0xFF4A545E)
    val toggleTrackOff = Color(0xFF3A424A)
    val criticalRowBackground = Color(0xFF3A0A0A)
    val chartGridLine = Color(0xFF232A31)
    val chartGapLine = Color(0xFF39424A)
}

/**
 * Тема дашборда.
 *
 * Тёмная — точные цвета макета «Metrik Expressive» ([MetrikDarkColorScheme]). Светлая — схема
 * из [SeedColor] через MaterialKolor, как и раньше: макет светлую тему не описывает, поэтому её
 * не переделываем.
 *
 * Стиль палитры светлой темы — [PaletteStyle.TonalSpot]: сдержанная, малонасыщенная схема,
 * ближе всего к дефолтной Material 3 — подходит инструменту, где данные важнее оформления.
 * Контраст поднят на ступень выше дефолта ([Contrast.Medium]): цифры и статусы должны читаться
 * с одного взгляда, а не всматриванием — по этому дашборду проверяют, не упал ли сервис.
 */
@Composable
fun MetrikTheme(content: @Composable () -> Unit) {
    if (isSystemInDarkTheme()) {
        MaterialTheme(colorScheme = MetrikDarkColorScheme, content = content)
    } else {
        DynamicMaterialTheme(
            seedColor = SeedColor,
            isDark = false,
            style = PaletteStyle.TonalSpot,
            contrastLevel = Contrast.Medium.value,
            content = content,
        )
    }
}

/**
 * Единая шкала отступов. До темы отступы расставлялись произвольными `dp` по месту — карточки,
 * таблицы и графики «плыли» друг относительно друга.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
