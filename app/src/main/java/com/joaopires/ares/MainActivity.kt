// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.joaopires.ares.ui.AresApp
import com.joaopires.ares.ui.theme.AresTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AresTheme {
                AresApp()
            }
        }
    }
}
