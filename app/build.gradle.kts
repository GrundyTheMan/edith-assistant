import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// KSP configuration (AGP 9 with KSP)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

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
    }

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

    testOptions.unitTests.isReturnDefaultValues = true

    lint {
        abortOnError = true
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

    // C05: Room database (KSP) - org.json for simple List<String> TypeConverters
    ksp(libs.room.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // C02/C03: coroutines, settings storage (DataStore), JSON, HTTP
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)

    // C05: Instrumented tests (Room testing API, Compose UI testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
