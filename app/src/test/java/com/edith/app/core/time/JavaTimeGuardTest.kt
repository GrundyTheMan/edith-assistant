package com.edith.app.core.time

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * K1.3: `java.time` is being replaced by kotlinx-datetime package by package, so the packages that moved can't go back.
 * [stillOnJavaTime] shrinks as packages move (docs/design/kmp-transition.md §7); add nothing to it.
 */
class JavaTimeGuardTest {
    private val stillOnJavaTime = listOf("accounts", "blink", "google", "icloud", "jarvis", "microsoft", "ring")
    private val bridge = "core/time/JavaTimeBridge.kt"

    @Test
    fun movedPackagesDoNotUseJavaTime() {
        val root = File("src/main/java/com/edith/app")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown().filter { it.extension == "kt" }
            .map { it to it.relativeTo(root).invariantSeparatorsPath }
            .filter { (file, path) -> path != bridge && stillOnJavaTime.none { path.startsWith("$it/") } && file.readText().contains("java.time") }
            .map { it.second }.toList()
        assertTrue("java.time in packages that already moved to kotlinx-datetime: $offenders", offenders.isEmpty())
    }
}
