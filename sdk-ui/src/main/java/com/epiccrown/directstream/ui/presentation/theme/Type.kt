package com.epiccrown.directstream.ui.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = height.sp,
    )

val Typography =
    Typography(
        displayLarge = type(48, 56, FontWeight.SemiBold),
        displayMedium = type(40, 48, FontWeight.SemiBold),
        displaySmall = type(32, 40, FontWeight.SemiBold),
        headlineLarge = type(32, 40, FontWeight.SemiBold),
        headlineMedium = type(28, 36, FontWeight.SemiBold),
        headlineSmall = type(24, 32, FontWeight.SemiBold),
        titleLarge = type(20, 28, FontWeight.SemiBold),
        titleMedium = type(16, 24, FontWeight.SemiBold),
        titleSmall = type(14, 20, FontWeight.SemiBold),
        bodyLarge = type(16, 24),
        bodyMedium = type(14, 20),
        bodySmall = type(12, 18),
        labelLarge = type(14, 20, FontWeight.Medium),
        labelMedium = type(12, 16, FontWeight.Medium),
        labelSmall = type(12, 16, FontWeight.Medium),
    )
