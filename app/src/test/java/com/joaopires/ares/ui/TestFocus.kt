// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.junit4.ComposeContentTestRule

class FocusHolder {
    var manager: FocusManager? = null
}

@Composable
fun CaptureFocus(holder: FocusHolder) {
    holder.manager = LocalFocusManager.current
}

// A text field left focused after typing keeps its input session alive under Robolectric, which stops Paging in later tests.
fun ComposeContentTestRule.releaseFocus(holder: FocusHolder) {
    runOnUiThread { holder.manager?.clearFocus(force = true) }
    waitForIdle()
}
