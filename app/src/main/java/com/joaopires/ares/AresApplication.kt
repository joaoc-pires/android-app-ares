// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.app.Application

class AresApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
