// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.joaopires.ares.notifications.AresWorkerFactory

class AresApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(AresWorkerFactory { container }).build()

    override fun onCreate() {
        super.onCreate()
        container.startBackgroundWork()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
