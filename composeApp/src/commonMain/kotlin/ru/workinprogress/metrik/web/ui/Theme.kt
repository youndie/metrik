package ru.workinprogress.metrik.web.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.Contrast
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import org.jetbrains.compose.resources.Font
import ru.workinprogress.metrik.web.generated.resources.Res
import ru.workinprogress.metrik.web.generated.resources.RobotoFlex
import ru.workinprogress.metrik.web.generated.resources.RobotoMono

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
 * Веса, которые реально используются в дашборде (`grep FontWeight.` по всему модулю) — заводим
 * ровно под них, а не под весь диапазон, который поддерживают файлы шрифтов.
 */
private val MetrikFontWeights =
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)

/**
 * Roboto Flex — вариативный шрифт интерфейса, ось `opsz` (оптический размер) заведена отдельно от
 * веса: макет использует один и тот же файл с `opsz 32` для обычного текста и `opsz 96` для
 * заголовков (`docs/design/metrik-expressive.html` — `font-variation-settings:'opsz' …`), а не два
 * разных шрифта. [FontVariation.Settings] — единственный способ достать нужный инстанс из одного
 * файла: назначение статических Font на каждый вес/оптику плодило бы копии без причины.
 *
 * Реализация `Font()` в `compose.components.resources` для desktop и wasmJs — общий модуль
 * `FontResources.skiko.kt` (оба таргета рисуют через Skia), поэтому вариативные оси заводятся
 * одинаково на обеих платформах — отдельного fallback на статические веса не потребовалось.
 */
@Composable
private fun rememberFlexFamily(opticalSize: Float): FontFamily {
    val fonts =
        MetrikFontWeights.map { weight ->
            Font(
                resource = Res.font.RobotoFlex,
                weight = weight,
                style = FontStyle.Normal,
                variationSettings = FontVariation.Settings(weight, FontStyle.Normal, FontVariation.opticalSizing(opticalSize.sp)),
            )
        }
    return remember(fonts) { FontFamily(fonts) }
}

/** Roboto Mono — вариативный только по весу, оси `opsz` у него нет. */
@Composable
private fun rememberMonoFamily(): FontFamily {
    val fonts =
        MetrikFontWeights.map { weight ->
            Font(
                resource = Res.font.RobotoMono,
                weight = weight,
                style = FontStyle.Normal,
                variationSettings = FontVariation.Settings(weight, FontStyle.Normal),
            )
        }
    return remember(fonts) { FontFamily(fonts) }
}

/**
 * Mono-семейство отдаётся через композиционный локал, а не статическим полем: `Font()` из
 * compose-resources грузит файл асинхронно и до первой загрузки подставляет системный дефолт сам —
 * получить готовый `FontFamily` можно только изнутри композиции, под [MetrikTheme].
 */
private val LocalMonoFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/** Замена `FontFamily.Monospace` по дашборду — тот же Roboto Mono, что и в mono-надзаголовках. */
val MetrikMono: FontFamily
    @Composable get() = LocalMonoFontFamily.current

/**
 * Типографика: Roboto Flex на всех ролях, крупные роли (display/headline) — с `opsz 96`, роли
 * помельче (title/body/label) — с `opsz 32` (как в макете, см. [rememberFlexFamily]). Material 3
 * версии, на которую собран дашборд (1.9.0), не даёт конструктора «одно семейство на всю схему» —
 * приходится переопределять каждую роль дефолтной [Typography] отдельно, но размеры и интерлиньяж
 * остаются дефолтными Material 3.
 */
@Composable
private fun metrikTypography(): Typography {
    val bodyFamily = rememberFlexFamily(opticalSize = 32f)
    val displayFamily = rememberFlexFamily(opticalSize = 96f)
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = displayFamily),
        displayMedium = base.displayMedium.copy(fontFamily = displayFamily),
        displaySmall = base.displaySmall.copy(fontFamily = displayFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = displayFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = displayFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = bodyFamily),
        titleLarge = base.titleLarge.copy(fontFamily = bodyFamily),
        titleMedium = base.titleMedium.copy(fontFamily = bodyFamily),
        titleSmall = base.titleSmall.copy(fontFamily = bodyFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = bodyFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = bodyFamily),
        bodySmall = base.bodySmall.copy(fontFamily = bodyFamily),
        labelLarge = base.labelLarge.copy(fontFamily = bodyFamily),
        labelMedium = base.labelMedium.copy(fontFamily = bodyFamily),
        labelSmall = base.labelSmall.copy(fontFamily = bodyFamily),
    )
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
 *
 * Шрифты макета (M-88, [rememberFlexFamily]/[rememberMonoFamily]) заведены в обеих ветках темы —
 * тёмной и светлой: макет фиксирует только цвета тёмной схемы, а типографика от темы не зависит.
 */
@Composable
fun MetrikTheme(content: @Composable () -> Unit) {
    val typography = metrikTypography()
    val monoFamily = rememberMonoFamily()

    CompositionLocalProvider(LocalMonoFontFamily provides monoFamily) {
        if (isSystemInDarkTheme()) {
            MaterialTheme(colorScheme = MetrikDarkColorScheme, typography = typography, content = content)
        } else {
            DynamicMaterialTheme(
                seedColor = SeedColor,
                isDark = false,
                style = PaletteStyle.TonalSpot,
                contrastLevel = Contrast.Medium.value,
                typography = typography,
                content = content,
            )
        }
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
