import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Room writes its schema here at build time (C05). Commit app/schemas/: it's needed for future migrations.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-opt-in=kotlinx.coroutines.FlowPreview")
    }
}

// Release signing (docs/design/05-environment.md §6). The key and its passwords live outside the repo, in
// ~/.edith/ (made once by scripts/create-signing-key.sh; EDITH_KEYSTORE_PROPERTIES overrides the location).
// Without them (CI, other machines) release builds come out unsigned and scripts/release.sh refuses to continue.
val keystoreProperties = Properties().apply {
    val path = System.getenv("EDITH_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.edith/keystore.properties"
    val file = File(path)
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.edith.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.edith.app"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("edith.versionCode").get().toInt()
        versionName = providers.gradleProperty("edith.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // C14: ONNX Runtime's native library is ~35 MB per CPU type. Only 64-bit is kept: arm64 for phones,
        // x86_64 for the emulator (32-bit devices can't run the Android versions Edith targets anyway).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Native libraries compressed in the APK (extracted on install): a much smaller download for ONNX Runtime.
    packaging { jniLibs { useLegacyPackaging = true } }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
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

    // JVM unit tests run without Android: android.util.Log etc. return defaults instead of throwing.
    testOptions.unitTests.isReturnDefaultValues = true

    // Room migration tests read the exported schemas (C06b MigrationTest).
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

    lint {
        abortOnError = true
        // New library versions are adopted deliberately (01-architecture §1), not on every lint run.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // C02: core infrastructure (serialization, DataStore for secrets, OkHttp for network)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.core)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)

    // C05: Room (KSP — never kapt, lessons L1)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // C10: reading web pages and DuckDuckGo results
    implementation(libs.jsoup)
    implementation(libs.onnxruntime.android)   // C14: openWakeWord models (D4)
    implementation(libs.coil.compose)   // C15: attachment thumbnails

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // ui-test-junit4 pulls in Espresso 3.5, which fails on API 37 (InputManager.getInstance was removed).
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
}
