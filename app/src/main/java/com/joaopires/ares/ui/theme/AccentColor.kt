// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.theme

import androidx.compose.ui.graphics.Color

enum class AccentColor(val argb: Long) {
    Red(0xFFFF3B30),
    Orange(0xFFFF9500),
    Yellow(0xFFFFCC00),
    Green(0xFF34C759),
    Mint(0xFF00C7BE),
    Teal(0xFF30B0C7),
    Cyan(0xFF32ADE6),
    Blue(0xFF007AFF),
    Indigo(0xFF5856D6),
    Purple(0xFFAF52DE),
    Pink(0xFFFF2D55),
    Brown(0xFFA2845E);

    val color: Color get() = Color(argb)

    companion object {
        val Default = Pink
    }
}
