// SPDX-License-Identifier: GPL-3.0-or-later

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

lint {
    disable += "AndroidGradlePluginVersion"
}

tasks.test {
    useJUnit()
}

dependencies {
    implementation(project(":core:xml"))
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.readability4j)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(libs.xmlpull)
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}
