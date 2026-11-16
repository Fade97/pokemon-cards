// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere: AGP 9 has built-in Kotlin. Declaring it here only pins the Kotlin Gradle plugin
    // version that AGP compiles with, so it matches the Compose compiler plugin below.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
