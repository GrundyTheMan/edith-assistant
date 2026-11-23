// K1.1: the shared Kotlin Multiplatform module. A KMP library has one Android variant, no BuildConfig and no build types:
// those stay in :app (docs/design/kmp-transition.md §3.4 in the public repo).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    android {
        namespace = "com.edith.app.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest { }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The dates and times of every shared package (K1.3). api: app/ uses the same types.
            api(libs.kotlinx.datetime)
            // K1.5: the database. api: the entities and DAOs carry Room's annotations and types.
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// K1.5: Room writes the schema here at build time (the same files the Android-only database wrote to app/schemas/; the
// identity hash in them is what tells an installed database it is the one the app expects). Commit shared/schemas/.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room's processor runs once per target that exists on this machine (the iPhone ones only exist on a Mac).
    listOf("kspAndroid", "kspJvm", "kspIosArm64", "kspIosSimulatorArm64").forEach { name ->
        if (configurations.names.contains(name)) add(name, libs.room.compiler)
    }
}
