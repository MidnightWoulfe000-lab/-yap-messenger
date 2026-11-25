package org.yapmessenger.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink = Color(0xFF06080E)
internal val Surface = Color(0xFF121620)
internal val Surface2 = Color(0xFF212839)
internal val Lime = Color(0xFFC7FF3C)
internal val Blue = Color(0xFF314CFF)
internal val Coral = Color(0xFFFF7958)
internal val Soft = Color(0xFFA8B2C7)
internal val White = Color(0xFFF7F8FF)
internal val NetLink = Color(0xFF6F86FF)

internal object YapSpacing {
    val gutter = 24.dp
    val gap = 12.dp
    val touchTarget = 48.dp
}

@Composable
internal fun YapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lime, onPrimary = Ink,
            secondary = Blue, onSecondary = White,
            secondaryContainer = Blue, onSecondaryContainer = White,
            tertiary = Coral, onTertiary = Ink,
            background = Ink, onBackground = White,
            surface = Surface, onSurface = White,
            surfaceVariant = Surface2, onSurfaceVariant = Soft,
            error = Coral, onError = Ink
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        ),
        content = content
    )
}
