package com.borodutch.spinwell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0B0B0E)
val Surface1 = Color(0xFF15151B)
val Surface2 = Color(0xFF1E1E26)
val Accent = Color(0xFFA798FF)
val Muted = Color(0xFFA0A0AE)

private val scheme = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF16112E), secondary = Accent, background = Bg, onBackground = Color(0xFFF2F2F7),
    surface = Bg, onSurface = Color(0xFFF2F2F7), surfaceVariant = Surface2, onSurfaceVariant = Muted,
    surfaceContainer = Surface1, surfaceContainerHigh = Surface2, surfaceContainerHighest = Color(0xFF282832),
    outlineVariant = Color(0xFF2A2A34), error = Color(0xFFFF8A80),
)

private val type = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SpinwellTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type) {
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
}
