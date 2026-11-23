package com.edith.app.tools.context

import com.edith.app.tools.framework.ParamType
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolParam
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import com.edith.app.core.time.formatPattern
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import java.util.Locale

/**
 * The current date and time, here or in another time zone (C08 §7a). Deterministic, so the tool framework can be
 * checked live with one real tool: "Thursday, October 29, 2026, 2:30 PM, America/Detroit (UTC-4)".
 */
class CurrentTimeTool(private val locale: () -> Locale = { Locale.US }) : Tool {
    override val spec = ToolSpec(
        name = "get_current_time",
        description = "Get the current date and time, here or in another time zone.",
        parameters = listOf(
            ToolParam(
                "timezone", ParamType.String,
                "IANA time zone such as Europe/London; leave out for the owner's own time zone",
                aliases = listOf("time_zone", "tz", "zone"),
            ),
        ),
    )
    override val statusLabel = "Checking the time…"
    override val displayName = "time"

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val requested = args.string("timezone")
        val zone = try {
            requested?.let { TimeZone.of(it) } ?: ctx.clock.zone()
        } catch (_: IllegalTimeZoneException) {
            return ToolResult.Error("Unknown time zone '$requested'. Use an IANA name such as America/New_York.")
        }
        val now = ctx.clock.now()
        val offset = zone.offsetAt(now).totalSeconds.let { s ->
            if (s == 0) "UTC" else "UTC%s%d%s".format(if (s < 0) "-" else "+", kotlin.math.abs(s) / 3600,
                (kotlin.math.abs(s) % 3600 / 60).let { m -> if (m == 0) "" else ":%02d".format(m) })
        }
        val text = formatPattern(now, zone, "EEEE, MMMM d, yyyy, h:mm a", locale().toLanguageTag())
        return ToolResult.Ok("$text, ${zone.id} ($offset)")
    }
}
