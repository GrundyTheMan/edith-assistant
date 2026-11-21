import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    // A desktop (JVM) target is for this spike's fast loop on Windows: shared code and UI run and are tested here.
    jvm()

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // K0.4: a database and a preferences file that both platforms use
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.datastore.preferences)
            implementation(libs.okio)
            // K0.5: navigation, ViewModel, images, icons
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.material.icons.extended)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.swing)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room's processor runs once per target that exists on this machine (the iPhone ones only exist on a Mac).
    listOf("kspJvm", "kspIosArm64", "kspIosSimulatorArm64").forEach { name ->
        if (configurations.names.contains(name)) add(name, libs.room.compiler)
    }
}

compose.desktop {
    application {
        mainClass = "spike.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "edith-kmp-spike"
            packageVersion = "1.0.0"
        }
    }
}
