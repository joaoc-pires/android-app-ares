// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joaopires.ares.core.data.AppSettings
import com.joaopires.ares.core.data.ThemeModeSetting
import com.joaopires.ares.ui.AresApp
import com.joaopires.ares.ui.addfeed.IncomingLink
import com.joaopires.ares.ui.addfeed.incomingLink
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.theme.AccentColor
import com.joaopires.ares.ui.theme.AresTheme
import com.joaopires.ares.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<IncomingLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming.value = intent?.toIncomingLink()
        val container = (application as AresApplication).container
        setContent {
            val link by incoming.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAppContainer provides container) {
                SettingsTheme(container) {
                    AresApp(incoming = link, onIncomingHandled = { incoming.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.toIncomingLink()?.let { incoming.value = it }
    }
}

private fun Intent.toIncomingLink(): IncomingLink? = incomingLink(
    action = action,
    data = dataString,
    type = type,
    text = getStringExtra(Intent.EXTRA_TEXT),
    stream = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
)

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
