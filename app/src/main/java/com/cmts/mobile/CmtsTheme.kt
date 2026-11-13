package com.cmts.mobile

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fixed CMTS brand colours; telemetry/status colours never follow wallpaper colours. */
internal object CmtsColors {
    val Gold = Color(0xFFD4AF37)
    val Success = Color(0xFFA3D5B5)
    val SuccessContainer = Color(0xFF213B2D)
    val Warning = Color(0xFFF2C47C)
    val WarningContainer = Color(0xFF423524)
    val Replay = Color(0xFFAAC7E8)
}

private val CmtsDarkColors = darkColorScheme(
    primary = CmtsColors.Gold, onPrimary = Color(0xFF211B05),
    primaryContainer = Color(0xFF39321D), onPrimaryContainer = Color(0xFFF3E5B0),
    inversePrimary = Color(0xFF715B09),
    secondary = Color(0xFFD2C6A4), onSecondary = Color(0xFF363021),
    secondaryContainer = Color(0xFF353128), onSecondaryContainer = Color(0xFFE9DFC6),
    tertiary = CmtsColors.Replay, onTertiary = Color(0xFF103248),
    tertiaryContainer = Color(0xFF263B4E), onTertiaryContainer = Color(0xFFD1E5FC),
    background = Color(0xFF111318), onBackground = Color(0xFFE7E1E5),
    surface = Color(0xFF111318), onSurface = Color(0xFFE7E1E5),
    surfaceVariant = Color(0xFF303238), onSurfaceVariant = Color(0xFFC9C5BB),
    surfaceTint = CmtsColors.Gold,
    surfaceDim = Color(0xFF111318), surfaceBright = Color(0xFF37393F),
    surfaceContainerLowest = Color(0xFF0C0E12), surfaceContainerLow = Color(0xFF1A1C21),
    surfaceContainer = Color(0xFF202228), surfaceContainerHigh = Color(0xFF292B31),
    surfaceContainerHighest = Color(0xFF33353B),
    inverseSurface = Color(0xFFE7E1E5), inverseOnSurface = Color(0xFF24262C),
    outline = Color(0xFF939087), outlineVariant = Color(0xFF45474E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF4B2927), onErrorContainer = Color(0xFFFFDAD5),
    scrim = Color.Black,
)

private val CmtsTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    displayMedium = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
internal fun CmtsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CmtsDarkColors,
        typography = CmtsTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
