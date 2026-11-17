// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "de.fabianduerkop.cardindex"
    // Platform android-37.0: AGP 9 addresses Android 17 by release and minor API level.
    compileSdk { version = release(37) { minorApiLevel = 0 } }

    defaultConfig {
        applicationId = "de.fabianduerkop.cardindex"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Backend API (backend/): prices (#11), language packs (#39), contributions (#14), deployed at cards-api.fdu.gg
        // until the final app domain exists (#15). Point a build at another backend (e.g. `wrangler dev`) with
        // `-Pcardindex.apiBaseUrl=https://…` (or the same line in ~/.gradle/gradle.properties);
        // the older `cardindex.priceApiBaseUrl` still works.
        val api = providers.gradleProperty("cardindex.apiBaseUrl")
            .orElse(providers.gradleProperty("cardindex.priceApiBaseUrl"))
            .getOrElse("https://cards-api.fdu.gg")
        buildConfigField("String", "API_BASE_URL", "\"$api\"")
    }

    buildTypes {
        debug {
            // Own package, so a debug build installs next to a release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // OpenCV ships ~180 MB of native code for four ABIs; debug builds need only phones and the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing config yet: assembleRelease produces an unsigned APK.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    // MigrationTestHelper reads the exported schemas as assets. Robolectric unit tests only see the app's merged
    // assets (test source-set assets are ignored), so the schemas go into debug builds. Release builds stay clean.
    sourceSets { getByName("debug").assets.directories.add("$projectDir/schemas") }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.text.recognition.latin)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.opencv)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
