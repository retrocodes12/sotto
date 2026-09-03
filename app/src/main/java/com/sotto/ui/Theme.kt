package com.sotto.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sotto.R

/** Warm paper and warm near-black, one yellow for what you say. */
object SottoColors {
    val paper = Color(0xFFF4F2EC)
    val ink = Color(0xFF141311)
    val tileLight = Color(0xFFE7E4DC)
    val mutedLight = Color(0xFF77746C)
    val hairlineLight = Color(0xFFD6D2C8)

    val night = Color(0xFF141311)
    val bone = Color(0xFFF2EFE7)
    val tileDark = Color(0xFF242320)
    val mutedDark = Color(0xFF8F8B82)
    val hairlineDark = Color(0xFF34322E)

    val accent = Color(0xFFFFD23F)
    val danger = Color(0xFFD9442B)
}

@OptIn(ExperimentalTextApi::class)
private val archivo = FontFamily(
    Font(R.font.archivo, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.archivo, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.archivo, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.archivo, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

val SottoType = Typography(
    // the wordmark
    displayLarge = TextStyle(fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-0.05).em),
    displayMedium = TextStyle(fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.04).em),
    titleLarge = TextStyle(fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em),
    titleMedium = TextStyle(fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = archivo, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = archivo, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = archivo, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    // micro caps: uppercase these in the UI
    labelSmall = TextStyle(fontFamily = archivo, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
)

val SottoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SottoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = SottoColors.accent, onPrimary = SottoColors.ink,
        secondary = SottoColors.bone, onSecondary = SottoColors.night,
        background = SottoColors.night, onBackground = SottoColors.bone,
        surface = SottoColors.night, onSurface = SottoColors.bone,
        surfaceVariant = SottoColors.tileDark, onSurfaceVariant = SottoColors.mutedDark,
        surfaceContainer = SottoColors.tileDark, surfaceContainerHigh = SottoColors.tileDark, surfaceContainerLow = SottoColors.night,
        outline = SottoColors.hairlineDark, outlineVariant = SottoColors.hairlineDark,
        error = SottoColors.danger, onError = SottoColors.bone,
    ) else lightColorScheme(
        primary = SottoColors.accent, onPrimary = SottoColors.ink,
        secondary = SottoColors.ink, onSecondary = SottoColors.paper,
        background = SottoColors.paper, onBackground = SottoColors.ink,
        surface = SottoColors.paper, onSurface = SottoColors.ink,
        surfaceVariant = SottoColors.tileLight, onSurfaceVariant = SottoColors.mutedLight,
        surfaceContainer = SottoColors.tileLight, surfaceContainerHigh = SottoColors.tileLight, surfaceContainerLow = SottoColors.paper,
        outline = SottoColors.hairlineLight, outlineVariant = SottoColors.hairlineLight,
        error = SottoColors.danger, onError = SottoColors.paper,
    )
    MaterialTheme(colorScheme = scheme, typography = SottoType, shapes = SottoShapes, content = content)
}
