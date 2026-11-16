// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentColorTest {

    @Test
    fun hasTwelveAccents() {
        assertEquals(12, AccentColor.entries.size)
    }

    @Test
    fun defaultIsPink() {
        assertEquals(AccentColor.Pink, AccentColor.Default)
    }

    @Test
    fun valuesMatchIos() {
        val expected = mapOf(
            AccentColor.Red to 0xFFFF3B30,
            AccentColor.Orange to 0xFFFF9500,
            AccentColor.Yellow to 0xFFFFCC00,
            AccentColor.Green to 0xFF34C759,
            AccentColor.Mint to 0xFF00C7BE,
            AccentColor.Teal to 0xFF30B0C7,
            AccentColor.Cyan to 0xFF32ADE6,
            AccentColor.Blue to 0xFF007AFF,
            AccentColor.Indigo to 0xFF5856D6,
            AccentColor.Purple to 0xFFAF52DE,
            AccentColor.Pink to 0xFFFF2D55,
            AccentColor.Brown to 0xFFA2845E,
        )
        assertEquals(expected, AccentColor.entries.associateWith { it.argb })
    }
}
