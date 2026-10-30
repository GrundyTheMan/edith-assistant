// Writes every Gradle build of Edith to build-logs/ (docs/design/05-environment.md §2), however it was started:
// scripts/*.sh, ./gradlew.bat run directly, or Android Studio. Applied from settings.gradle.kts.
//
// What's captured: a header (what ran, from where, branch and commit), each task with its outcome, everything
// tasks print (compiler errors and warnings, test failures, lint), and a footer with BUILD SUCCESSFUL/FAILED,
// the duration and Gradle's failure message. Gradle's own progress bar isn't output, so it isn't in the log.
//
// Not logged: Android Studio's project sync (not a build), and builds started by scripts/*.sh, which already
// record the whole terminal output in their own log (they set EDITH_LOG).
// Logging must never break a build, so every failure in here is swallowed.

import org.gradle.api.flow.BuildWorkResult
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.logging.StandardOutputListener
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Input
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** All writes to a build log go through here, so parallel tasks can't interleave mid-line. Never throws. */
object BuildLogFile {
    @Synchronized fun append(path: String, text: String) {
        try { File(path).appendText(text) } catch (e: Exception) { }
    }
}

/** Receives everything tasks print. Bare task-status lines are skipped: [BuildLogTasks] writes those reliably. */
class BuildLogWriter(private val path: String) : StandardOutputListener {
    // ":app:compileDebugKotlin", ":app:preBuild UP-TO-DATE", or the status arriving on its own (" UP-TO-DATE").
    private val statusLine = Regex("""^(:[\w:.-]+( [A-Z-]+)?| [A-Z-]+)\s*$""")
    override fun onOutput(output: CharSequence) {
        if (!statusLine.matches(output)) BuildLogFile.append(path, output.toString())
    }
}

/** Writes one "> Task :path OUTCOME" line as each task finishes (Gradle's task-completion events). */
abstract class BuildLogTasks : BuildService<BuildLogTasks.Params>, OperationCompletionListener {
    interface Params : BuildServiceParameters {
        val logPath: Property<String>
    }

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val outcome = when (val result = event.result) {
            is TaskFailureResult -> "FAILED"
            is TaskSkippedResult -> result.skipMessage.uppercase()
            is TaskSuccessResult -> when {
                result.isFromCache -> "FROM-CACHE"
                result.isUpToDate -> "UP-TO-DATE"
                else -> ""
            }
            else -> ""
        }
        BuildLogFile.append(parameters.logPath.get(), "> Task ${event.descriptor.taskPath} $outcome".trimEnd() + "\n")
    }
}

abstract class BuildLogFinish : FlowAction<BuildLogFinish.Params> {
    interface Params : FlowParameters {
        @get:Input val logPath: Property<String>
        @get:Input val startedAtMillis: Property<Long>
        @get:Input val keep: Property<Int>
        @get:Input val result: Property<BuildWorkResult>
    }

    override fun execute(parameters: Params) {
        try {
            val log = File(parameters.logPath.get())
            val seconds = (System.currentTimeMillis() - parameters.startedAtMillis.get()) / 1000
            val failure = parameters.result.get().failure.orElse(null)
            val footer = buildString {
                appendLine()
                if (failure == null) {
                    appendLine("BUILD SUCCESSFUL in ${seconds}s")
                } else {
                    appendLine("BUILD FAILED in ${seconds}s")
                    appendLine()
                    appendLine("* What went wrong:")
                    var cause: Throwable? = failure
                    val seen = mutableSetOf<String>()
                    while (cause != null) {
                        val message = cause.message?.trim()
                        if (!message.isNullOrEmpty() && seen.add(message)) appendLine(message)
                        cause = cause.cause
                    }
                }
            }
            BuildLogFile.append(log.absolutePath, footer)
            val dir = log.parentFile
            log.copyTo(File(dir, "latest.log"), overwrite = true)
            dir.listFiles { f -> f.name.matches(Regex("""\d{4}-\d\d-\d\d_.*\.log""")) }
                ?.sortedByDescending { it.name }
                ?.drop(parameters.keep.get())
                ?.forEach { it.delete() }
        } catch (e: Exception) {
        }
    }
}

abstract class BuildLogPlugin @Inject constructor(
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
    private val buildEvents: BuildEventsListenerRegistry,
) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        try {
            if (System.getProperty("idea.sync.active") == "true") return     // Android Studio sync
            if (!System.getenv("EDITH_LOG").isNullOrEmpty()) return          // a script is already logging
            val start = settings.gradle.startParameter
            val requested = start.taskNames.joinToString(" ").ifEmpty { "(default tasks)" }
            val now = LocalDateTime.now()
            val dir = File(settings.rootDir, "build-logs").apply { mkdirs() }
            val slug = requested.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(60)
            val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            // Two builds started in the same second (e.g. Android Studio and a terminal) get separate files.
            val log = generateSequence(1) { it + 1 }
                .map { n -> File(dir, "${stamp}_gradle_$slug${if (n == 1) "" else "-$n"}.log") }
                .first { it.createNewFile() }
            val fromStudio = start.projectProperties.containsKey("android.injected.invoked.from.ide") ||
                System.getProperty("idea.active") == "true"
            log.writeText(
                "# Gradle build: $requested | ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}" +
                    " | from ${if (fromStudio) "Android Studio" else "command line"} | ${git(settings)}\n\n"
            )

            val tasks = settings.gradle.sharedServices.registerIfAbsent("edithBuildLog", BuildLogTasks::class.java) {
                parameters.logPath.set(log.absolutePath)
            }
            buildEvents.onTaskCompletion(tasks)

            val writer = BuildLogWriter(log.absolutePath)
            settings.gradle.allprojects {
                val project: Project = this
                project.logging.addStandardOutputListener(writer)
                project.logging.addStandardErrorListener(writer)
                project.tasks.configureEach {
                    val task: Task = this
                    task.logging.addStandardOutputListener(writer)
                    task.logging.addStandardErrorListener(writer)
                }
            }

            flowScope.always(BuildLogFinish::class.java) {
                parameters.logPath.set(log.absolutePath)
                parameters.startedAtMillis.set(System.currentTimeMillis())
                parameters.keep.set(System.getenv("EDITH_LOG_KEEP")?.toIntOrNull() ?: 30)
                parameters.result.set(flowProviders.buildWorkResult)
            }
        } catch (e: Exception) {
        }
    }

    /** "branch@commit", plus a note when there are uncommitted changes. */
    private fun git(settings: Settings): String = try {
        // providers.exec: Gradle's supported way to run a command while configuring (plain processes are deprecated).
        fun run(vararg args: String) = settings.providers.exec {
            commandLine("git", *args)
            workingDir = settings.rootDir
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        val dirty = if (run("status", "--porcelain").isNotEmpty()) " (with uncommitted changes)" else ""
        "${run("rev-parse", "--abbrev-ref", "HEAD")}@${run("rev-parse", "--short", "HEAD")}$dirty"
    } catch (e: Exception) {
        "git: unknown"
    }
}

apply<BuildLogPlugin>()
