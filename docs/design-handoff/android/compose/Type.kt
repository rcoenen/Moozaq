package com.moozaq.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Roboto Flex: bundle res/font/roboto_flex.ttf or use the Google Fonts downloadable font provider.
val RobotoFlex: FontFamily = FontFamily.Default // replace with the Roboto Flex FontFamily

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double) =
    TextStyle(fontFamily = RobotoFlex, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

val MoozaqTypography = Typography(
    titleLarge = style(22, 28, FontWeight.Normal, 0.0),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = style(16, 24, FontWeight.Normal, 0.5),
    bodyMedium = style(14, 20, FontWeight.Normal, 0.25),
    bodySmall = style(12, 16, FontWeight.Normal, 0.4),
    labelLarge = style(14, 20, FontWeight.Medium, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.5)
)

// Now playing title: titleLarge at SemiBold, max 2 lines.
val NowPlayingTitle = style(22, 28, FontWeight.SemiBold, 0.0)
