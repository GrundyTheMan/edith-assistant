package com.edith.app.home

/** Which device a name means (C25 §3). */
sealed interface DeviceMatch {
    data class Found(val device: HomeDevice) : DeviceMatch
    data class Ambiguous(val candidates: List<HomeDevice>) : DeviceMatch
    data object NotFound : DeviceMatch
}

/**
 * Name resolution in the tool layer (C25 §3, Jarvis's `matchDevice` plus rooms). In order: the exact name (any case);
 * "room name" or "name in room"; then the only device whose name (or room and name) contains every word. A
 * category a model copied from the list ("Front door (Lock)") is dropped and tried again. No fuzzier matching: an
 * unclear name lists the candidates, so Edith asks.
 */
object DeviceNames {
    fun match(devices: List<HomeDevice>, name: String): DeviceMatch {
        val q = norm(name)
        if (q.isEmpty()) return DeviceMatch.NotFound
        val result = matchNormalized(devices, q)
        if (result == DeviceMatch.NotFound) {
            val bare = name.replace(Regex("""\s*\([^()]*\)\s*$"""), "")
            if (bare != name && bare.isNotBlank()) return matchNormalized(devices, norm(bare))
        }
        return result
    }

    private fun matchNormalized(devices: List<HomeDevice>, q: String): DeviceMatch {
        one(devices.filter { norm(it.name) == q })?.let { return it }
        val q2 = q.removePrefix("the ")
        one(devices.filter { norm(it.name) == q2 })?.let { return it }
        // "kitchen light" = room "Kitchen", name "Light"; "light in the kitchen" too.
        one(devices.filter { d -> d.room != null && withRoom(d).any { it == q2 } })?.let { return it }
        val words = q2.split(' ').filter { it.isNotEmpty() && it != "the" && it != "in" }
        val partial = devices.filter { d ->
            val text = norm(listOfNotNull(d.room, d.name).joinToString(" "))
            words.all { w -> text.split(' ').any { it.startsWith(w) } }
        }
        return when (partial.size) {
            0 -> DeviceMatch.NotFound
            1 -> DeviceMatch.Found(partial[0])
            else -> DeviceMatch.Ambiguous(partial)
        }
    }

    /** One device → found; several with the same name → ambiguous; none → keep looking (null). */
    private fun one(list: List<HomeDevice>): DeviceMatch? = when (list.size) {
        0 -> null
        1 -> DeviceMatch.Found(list[0])
        else -> DeviceMatch.Ambiguous(list)
    }

    private fun withRoom(d: HomeDevice): List<String> {
        val room = norm(d.room!!)
        val name = norm(d.name)
        return listOf("$room $name", "$name in $room", "$name in the $room")
    }

    /** "The Porch-Light's" → "the porch light's": lower case, one space, dashes and underscores as spaces. */
    fun norm(s: String): String = s.lowercase().replace(Regex("[-_]"), " ").replace(Regex("""\s+"""), " ").trim()

    /** The tool's words for an unclear or unknown name (C25 §3): up to 10 candidates. */
    fun problem(name: String, match: DeviceMatch): String = when (match) {
        is DeviceMatch.Ambiguous -> {
            val shown = match.candidates.take(10).joinToString { d -> "\"${d.name}\"" + (d.room?.let { " ($it)" } ?: "") }
            val more = if (match.candidates.size > 10) " and ${match.candidates.size - 10} more" else ""
            "\"$name\" could mean $shown$more. Ask the owner which one, then use its exact name."
        }
        else -> "No device is called \"$name\". Use home_devices to see their names."
    }
}
