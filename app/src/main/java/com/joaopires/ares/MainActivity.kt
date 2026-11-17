// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joaopires.ares.core.data.AppSettings
import com.joaopires.ares.core.data.ThemeModeSetting
import com.joaopires.ares.ui.AresApp
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.theme.AccentColor
import com.joaopires.ares.ui.theme.AresTheme
import com.joaopires.ares.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AresApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                SettingsTheme(container) { AresApp() }
            }
        }
    }
}

@Composable
fun SettingsTheme(container: AppContainer, content: @Composable () -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    AresTheme(
        mode = when (settings.themeMode) {
            ThemeModeSetting.System -> ThemeMode.System
            ThemeModeSetting.Light -> ThemeMode.Light
            ThemeModeSetting.Dark -> ThemeMode.Dark
        },
        accent = AccentColor.entries.firstOrNull { it.name == settings.accent } ?: AccentColor.Default,
        dynamicColor = settings.dynamicColor,
        content = content,
    )
}
