package com.edith.app.printer.feed

/**
 * What the owner types into the page's boxes, and the commands made from it (C39 part 8.4, stage 2). Every function returns null when
 * the text is not usable, and the button that needs it stays disabled: an empty box never sends anything. Jarvis checks every value
 * again; these only keep obvious nonsense from being sent.
 */
internal object PrinterInput {
    /** A number as a person types it: "1.5" or "1,5", blanks around it ignored. Null for nothing, text, NaN or infinity. */
    fun number(text: String): Double? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** A number in a range (both ends allowed), or null. */
    fun between(text: String, min: Double, max: Double): Double? = number(text)?.takeIf { it in min..max }

    /** Sets a heater's target: 0 up to the highest target Klipper allows for it. */
    fun setTemp(heater: String, text: String, maxTemp: Double): PrinterCommand? =
        between(text, 0.0, if (maxTemp > 0) maxTemp else 300.0)?.let { PrinterCommand.SetTemp(heater, it) }

    /** Extrude or retract: a length above zero (at most 100 mm at a time) and a speed above zero (at most 50 mm/s). */
    fun extrude(lengthText: String, speedText: String, retract: Boolean): PrinterCommand? {
        val length = between(lengthText, 0.01, 100.0) ?: return null
        val speed = between(speedText, 0.1, 50.0) ?: return null
        return PrinterCommand.Extrude(if (retract) -length else length, speed)
    }

    /**
     * The speed limits: only the boxes that hold a number are sent; a box left empty is left alone. Nothing typed at all, or a number that
     * cannot be right (zero or negative), is null. The cruise ratio is a fraction from 0 to 1.
     */
    fun limits(velocity: String, accel: String, scv: String, cruiseRatio: String): PrinterCommand.Limits? {
        val v = velocity.trim().takeIf { it.isNotEmpty() }?.let { number(it)?.takeIf { n -> n > 0 } ?: return null }
        val a = accel.trim().takeIf { it.isNotEmpty() }?.let { number(it)?.takeIf { n -> n > 0 } ?: return null }
        val s = scv.trim().takeIf { it.isNotEmpty() }?.let { number(it)?.takeIf { n -> n > 0 } ?: return null }
        val c = cruiseRatio.trim().takeIf { it.isNotEmpty() }?.let { between(it, 0.0, 1.0) ?: return null }
        if (v == null && a == null && s == null && c == null) return null
        return PrinterCommand.Limits(velocity = v, accel = a, scv = s, cruiseRatio = c)
    }

    /** Firmware retraction: the same rule (empty boxes are left alone; negative is wrong; at least one number). */
    fun retraction(length: String, speed: String, extraLength: String, unretractSpeed: String): PrinterCommand.Retraction? {
        fun pick(t: String, allowZero: Boolean): Double? = t.trim().takeIf { it.isNotEmpty() }?.let { number(it)?.takeIf { n -> if (allowZero) n >= 0 else n > 0 } ?: return@let Double.NaN }
        val l = pick(length, true); val sp = pick(speed, false); val ex = pick(extraLength, true); val un = pick(unretractSpeed, false)
        if (listOf(l, sp, ex, un).any { it != null && it.isNaN() }) return null
        if (l == null && sp == null && ex == null && un == null) return null
        return PrinterCommand.Retraction(length = l, speed = sp, extraLength = ex, unretractSpeed = un)
    }

    /** The console takes one line of at most 200 characters; an empty line, a longer one or one with a line break is not sent. */
    const val MAX_LINE = 200
    fun consoleLine(text: String): PrinterCommand.Gcode? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LINE || t.any { it.code == 10 || it.code == 13 }) return null
        return PrinterCommand.Gcode(t)
    }

    /** A percentage the slider gives (whole numbers only; the server's range is enforced there). */
    fun percent(value: Float): Double = Math.round(value).toDouble()

    /** A 0 to 1 level from a slider that goes from 0 to 100. */
    fun level(value: Float): Double = Math.round(value) / 100.0

    /** The jog steps in mm. 100 mm is only offered for X and Y. */
    val STEPS = listOf(0.1, 1.0, 10.0, 50.0, 100.0)
    fun stepsFor(axis: String): List<Double> = if (axis == Axis.Z) STEPS.filter { it < 100.0 } else STEPS

    /** The Z offset buttons: what each adds, in mm. */
    val Z_OFFSET_STEPS = listOf(0.01, 0.05, 0.1)

    /** A step as it is written on its chip: 0.1, 1, 10. */
    fun stepLabel(step: Double): String = if (step % 1.0 == 0.0) step.toInt().toString() else step.toString()
}
