// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.aboutlibraries) apply false
}

val bannedGroups = Regex(
    """^(com\.google\.android\.(gms|play|ump|datatransport)|com\.google\.firebase|com\.google\.mlkit|com\.android\.billingclient|com\.android\.installreferrer)(\..+)?$"""
)

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (bannedGroups.matches(requested.group)) {
                throw GradleException(
                    "Banned dependency '${requested.group}:${requested.name}' in configuration '$name'. " +
                        "Ares must not depend on Google Play Services, Firebase or other proprietary Google libraries."
                )
            }
        }
    }
}
