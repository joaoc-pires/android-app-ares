// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeFidelity

fun accentColorScheme(accent: AccentColor, dark: Boolean): ColorScheme {
    val scheme = SchemeFidelity(
        sourceColorHct = Hct.fromInt(accent.argb.toInt()),
        isDark = dark,
        contrastLevel = 0.0,
    )
    val colors = MaterialDynamicColors()
    fun DynamicColor.resolve() = Color(getArgb(scheme))

    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.primary.resolve(),
        onPrimary = colors.onPrimary.resolve(),
        primaryContainer = colors.primaryContainer.resolve(),
        onPrimaryContainer = colors.onPrimaryContainer.resolve(),
        inversePrimary = colors.inversePrimary.resolve(),
        secondary = colors.secondary.resolve(),
        onSecondary = colors.onSecondary.resolve(),
        secondaryContainer = colors.secondaryContainer.resolve(),
        onSecondaryContainer = colors.onSecondaryContainer.resolve(),
        tertiary = colors.tertiary.resolve(),
        onTertiary = colors.onTertiary.resolve(),
        tertiaryContainer = colors.tertiaryContainer.resolve(),
        onTertiaryContainer = colors.onTertiaryContainer.resolve(),
        background = colors.background.resolve(),
        onBackground = colors.onBackground.resolve(),
        surface = colors.surface.resolve(),
        onSurface = colors.onSurface.resolve(),
        surfaceVariant = colors.surfaceVariant.resolve(),
        onSurfaceVariant = colors.onSurfaceVariant.resolve(),
        surfaceTint = colors.surfaceTint.resolve(),
        inverseSurface = colors.inverseSurface.resolve(),
        inverseOnSurface = colors.inverseOnSurface.resolve(),
        error = colors.error.resolve(),
        onError = colors.onError.resolve(),
        errorContainer = colors.errorContainer.resolve(),
        onErrorContainer = colors.onErrorContainer.resolve(),
        outline = colors.outline.resolve(),
        outlineVariant = colors.outlineVariant.resolve(),
        scrim = colors.scrim.resolve(),
        surfaceBright = colors.surfaceBright.resolve(),
        surfaceDim = colors.surfaceDim.resolve(),
        surfaceContainer = colors.surfaceContainer.resolve(),
        surfaceContainerHigh = colors.surfaceContainerHigh.resolve(),
        surfaceContainerHighest = colors.surfaceContainerHighest.resolve(),
        surfaceContainerLow = colors.surfaceContainerLow.resolve(),
        surfaceContainerLowest = colors.surfaceContainerLowest.resolve(),
    )
}
