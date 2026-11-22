// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.aboutlibraries)
}

// Release signing with the upload key (#88). Play App Signing re-signs for the store, so this key only proves uploads.
// Values come from environment variables or the git-ignored android/keystore.properties; nothing secret is in the
// repo. The environment wins per value. Without any of them, release builds stay unsigned (CI checks R8 that way).
// android/README.md, "Signed release".
val uploadSigning: Map<String, String> = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties()
    if (file.isFile) file.inputStream().use { props.load(it) }
    val keys = mapOf(
        "storeFile" to "UPLOAD_KEYSTORE_FILE",
        "storePassword" to "UPLOAD_KEYSTORE_PASSWORD",
        "keyAlias" to "UPLOAD_KEY_ALIAS",
        "keyPassword" to "UPLOAD_KEY_PASSWORD",
    )
    val values = keys.mapNotNull { (prop, env) ->
        (providers.environmentVariable(env).orNull ?: props.getProperty(prop))?.takeIf { it.isNotBlank() }?.let { prop to it }
    }.toMap()
    require(values.isEmpty() || values.size == keys.size) {
        "Release signing is incomplete, missing: " + keys.filterKeys { it !in values }.values.joinToString() +
            " (environment) or the same keys in keystore.properties"
    }
    values
}

android {
    // Kotlin package. Deliberately separate from the applicationId and never renamed (#15).
    namespace = "de.fabianduerkop.cardindex"
    // Platform android-37.0: AGP 9 addresses Android 17 by release and minor API level.
    compileSdk { version = release(37) { minorApiLevel = 0 } }

    defaultConfig {
        // Play package (#15). It can never change after the first upload to Play.
        applicationId = "de.fabianduerkop.foilark"
        minSdk = 26
        targetSdk = 36
        // Every Play upload needs a higher versionCode. release-bundle.sh passes the UTC time as yyMMddHH
        // (`-Pfoilark.versionCode=26112215`); local and CI builds stay at 1.
        versionCode = providers.gradleProperty("foilark.versionCode").map(String::toInt).getOrElse(1)
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
        // Pro test token for debug builds (#17) until sign-in and billing exist (#16); empty in release builds. Set
        // `cardindex.proTestToken=…` in ~/.gradle/gradle.properties, never in the repo. backend/README.md, "Price history".
        buildConfigField("String", "PRO_TEST_TOKEN", "\"\"")
        // The privacy policy the Play listing links to, served by the same Worker (#70).
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${api.trimEnd('/')}/privacy\"")
    }

    signingConfigs {
        if (uploadSigning.isNotEmpty()) {
            create("upload") {
                // A relative storeFile is resolved against android/, where keystore.properties lives.
                storeFile = rootProject.file(uploadSigning.getValue("storeFile"))
                storePassword = uploadSigning.getValue("storePassword")
                keyAlias = uploadSigning.getValue("keyAlias")
                keyPassword = uploadSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Own package, so a debug build installs next to a release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            val proToken = providers.gradleProperty("cardindex.proTestToken").getOrElse("")
            // The backend's format (backend/src/entitlements.ts): anything else is no token at all there (401).
            require(proToken.isEmpty() || proToken.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {
                "cardindex.proTestToken must be 32–128 URL-safe characters"
            }
            buildConfigField("String", "PRO_TEST_TOKEN", "\"$proToken\"")
            // OpenCV ships ~180 MB of native code for four ABIs; debug builds need only phones and the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the upload key when it is configured (top of this file), otherwise unsigned.
            signingConfigs.findByName("upload")?.let { signingConfig = it }
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

// Open-source licenses (#136): the Android plugin writes R.raw.aboutlibraries for each variant at build time, from
// the POMs of the dependencies that variant packages. LicensesScreen reads it; nothing is fetched at runtime.
aboutLibraries {
    // BOMs only pin versions; they ship no code.
    collect { includePlatform = false }
    export {
        // Developer names and funding links aren't shown; keep the generated file small.
        excludeFields.addAll("developers", "funding")
    }
}

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
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
