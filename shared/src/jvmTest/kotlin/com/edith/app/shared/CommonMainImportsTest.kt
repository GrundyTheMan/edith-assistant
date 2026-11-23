package com.edith.app.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Shared code must compile for the iPhone, so `commonMain` may not import Android, the JVM's own classes or OkHttp (dependency rule, kmp-transition.md §4.2). */
class CommonMainImportsTest {
    private val forbidden = listOf("import android.", "import androidx.core.", "import java.", "import javax.", "import okhttp3.", "import okio.")

    @Test
    fun commonMainImportsNothingPlatformSpecific() {
        val root = File("src/commonMain")
        assertTrue(root.isDirectory, "run from the shared module: ${root.absolutePath}")
        val offenders = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filter { line -> forbidden.any { line.trim().startsWith(it) } }.map { "${file.name}: ${it}" }
        }.toList()
        assertTrue(offenders.isEmpty(), "Platform imports in commonMain: $offenders")
    }
}
