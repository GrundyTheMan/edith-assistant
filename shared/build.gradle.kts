// K1.1: the shared Kotlin Multiplatform module. A KMP library has one Android variant, no BuildConfig and no build types:
// those stay in :app (docs/design/kmp-transition.md §3.4 in the public repo).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    android {
        namespace = "com.edith.app.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest { isReturnDefaultValues = true }   // the shared code logs through android.util.Log, which a host test cannot run
        // The app is built for Java 17, and it inlines functions from here: they must be built for the same.
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The dates and times of every shared package (K1.3). api: app/ uses the same types.
            api(libs.kotlinx.datetime)
            // K1.5: the database. api: the entities and DAOs carry Room's annotations and types.
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            api(libs.kotlinx.serialization.json)   // OllamaClient takes a Json
            api(libs.ktor.client.core)             // and an HttpClient
            api(libs.datastore.preferences.core)   // the settings
            implementation(libs.ksoup)             // reading web pages and search results
            implementation(libs.coroutines.core)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)   // K4.1: the iPhone HTTP engine
            implementation(libs.okio)                 // K4.1: DataStore on the iPhone takes an okio path
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)   // K2.12: a pretend HTTP engine for the container test
        }
        // K2 G2: the chat engine's end-to-end tests need a Room database, which the Android host test cannot build without a Context.
        // They are plain common Kotlin in src/engineTest, compiled for the desktop and the iPhone (each has its own inMemoryTestDatabase()).
        val engineTests = file("src/engineTest/kotlin")
        jvmTest { kotlin.srcDir(engineTests) }
        iosTest { kotlin.srcDir(engineTests) }
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
