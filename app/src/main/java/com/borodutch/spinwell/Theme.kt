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
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    )
}

@Composable
fun SpinwellTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type) {
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
}

/** Letter spacing for small caps labels; cursive scripts (Arabic) and conjunct scripts must not be spaced out or their letters disconnect. */
fun caps(sp: Float) = (if (java.util.Locale.getDefault().language in setOf("ar", "hi", "th")) 0f else sp).sp
