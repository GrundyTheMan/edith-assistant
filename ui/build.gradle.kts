// K3.0: the shared screens (Compose Multiplatform). Targets: Android (the library plugin, like :shared), the desktop (a window to see screens on Windows),
// and the iPhone. docs/design/kmp-parity-plan.md §4.1 and K3.0. It depends on :shared and knows nothing of :app.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.edith.app.ui"
        compileSdk = 37
        minSdk = 26
        // No host tests: runComposeUiTest on Android needs an emulator or Robolectric. The UI tests run on the desktop and the iPhone simulator.
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // K4.1: the iPhone app (iosApp/) links this framework: the shared screens and, through :shared, the engine.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "EdithUI"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.coil.compose)   // K3.2: attachment thumbnails (Coil 3 is multiplatform)
            implementation(libs.compose.icons.extended)   // K3.1: every icon the Android app uses must exist here (IconsTest)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        // ./gradlew :ui:run shows the shared screens in a window on Windows.
        mainClass = "com.edith.app.ui.desktop.MainKt"
    }
}
