package com.edith.app.core.time

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * K1.3: the app's main code uses kotlinx-datetime and `kotlin.time`, not `java.time`, so it can move to shared code
 * for the iPhone. The platform-specific formatting lives in `shared/` (`formatPattern`), where java.time is allowed.
 */
class JavaTimeGuardTest {
    @Test
    fun mainCodeDoesNotUseJavaTime() {
        val root = File("src/main/java/com/edith/app")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.readText().contains("java.time") }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertTrue("java.time in: $offenders", offenders.isEmpty())
    }
}
