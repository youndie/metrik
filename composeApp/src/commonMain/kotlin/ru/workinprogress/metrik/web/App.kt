package ru.workinprogress.metrik.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ru.workinprogress.metrik.wire.PROTOCOL_VERSION

/**
 * Корень дашборда. Экраны (обзор, сервис, маршруты, медленные, система, алерты) приезжают в M5,
 * состав — `docs/services/metrik-web.md`.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("metrik · protocol v$PROTOCOL_VERSION")
            }
        }
    }
}
