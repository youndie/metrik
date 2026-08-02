package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Заметный чип «элемента честности» — разрыв на графике, приблизительность перцентиля, отсутствие
 * разреза по инстансам и т. п. В макете это стало видимой цветной плашкой вместо мелкой серой
 * подписи (см. docs §4 и задание) — сам текст при этом не меняется, только форма подачи.
 */
@Composable
fun HonestyChip(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = fontWeight,
            color = foreground,
        )
    }
}

@Composable
fun LoadingState(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xl),
    )
}

/** Мелкая карточка-плитка «значение над лейблом» (CPU/тредов/GC в системных карточках). */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceContainer,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MetrikExtra.dim,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = foreground)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MetrikMono,
            color = labelColor,
        )
    }
}
