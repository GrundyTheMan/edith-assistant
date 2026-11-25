package com.edith.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two modules may not each have a file of the same name in the same package when both hold top-level functions or values: Kotlin compiles each file's top-level
 * declarations into a class named after the file (`CalendarScreenKt`), the two classes then have the same name, and at run time one hides the other, so a call into the
 * hidden one is a `NoSuchMethodError`. It happened when the Calendar, To-do and Printer screens moved to `ui/` and Android's wrapper kept the file name (found on the
 * emulator, K3.8; nothing in the unit tests or CI loads those classes). Name an Android wrapper's file differently (`AndroidCalendarScreen.kt`).
 */
class FileFacadeCollisionTest {
    private val roots = listOf("src/main/java", "../shared/src/commonMain", "../shared/src/androidMain", "../shared/src/jvmMain", "../ui/src/commonMain", "../ui/src/androidMain")
    private val topLevel = Regex("""^(internal |private |public |inline |suspend |const |tailrec |operator |infix )*(fun|val|var) """, RegexOption.MULTILINE)

    @Test fun noTwoModulesMakeTheSameFileClass() {
        val files = roots.map { File(it) }.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" && !it.name.endsWith(".kt.orig") }.mapNotNull { f ->
                val text = f.readText()
                val pkg = Regex("""^package (\S+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                if (!topLevel.containsMatchIn(text)) return@mapNotNull null   // only classes: no file class is made
                "$pkg.${f.nameWithoutExtension}" to f.path.replace('\\', '/')
            }
        }
        val clashes = files.groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }
        assertTrue("Same file class in two places (rename the Android wrapper's file): $clashes", clashes.isEmpty())
    }
}
