// K1.1: the shared Kotlin Multiplatform module. A KMP library has one Android variant, no BuildConfig and no build types:
// those stay in :app (docs/design/kmp-transition.md §3.4 in the public repo).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
