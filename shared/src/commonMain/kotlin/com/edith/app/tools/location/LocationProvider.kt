package com.edith.app.tools.location

import com.edith.app.core.error.Outcome
import kotlin.math.roundToLong

/** Roughly where the phone is. Rounded to ~1 km: approximate location is all "here" needs. */
data class Coordinates(val latitude: Double, val longitude: Double) {
    fun rounded() = Coordinates(round2(latitude), round2(longitude))

    private companion object {
        fun round2(v: Double) = (v * 100).roundToLong() / 100.0
    }
}

/**
 * The phone's approximate location (C11), asked for only when the owner says "here". Coordinates are never stored or
 * logged, and never sent to Ollama: only the place's name goes in a tool result.
 */
interface LocationProvider {
    /** Failure details are plain sentences for the model. */
    suspend fun current(): Outcome<Coordinates>

    /** "Fowlerville, Michigan (US)", or null when the phone can't name the place. */
    suspend fun placeName(at: Coordinates): String?

    companion object {
        /** `Manifest.permission.ACCESS_COARSE_LOCATION`, spelled out so this file needs no Android. */
        const val PERMISSION = "android.permission.ACCESS_COARSE_LOCATION"
        const val DENIED = "The user hasn't shared their location — ask for a city."
        const val LOCATION_OFF = "Location is turned off on the phone — ask for a city, or say it can be turned on in Android's settings."
        const val NO_FIX = "Couldn't find the phone's location just now — ask for a city."
    }
}
