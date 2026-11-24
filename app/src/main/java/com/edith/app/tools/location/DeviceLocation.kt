package com.edith.app.tools.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * Android's `LocationManager`, no Google Play services: the fused provider (Android 12+), else the network one, else
 * GPS; one fresh fix within 10 s, else a recent last-known one. `ACCESS_COARSE_LOCATION` only — Android blurs the
 * result to about 2 km.
 */
class AndroidLocationProvider(private val context: Context, private val dispatchers: AppDispatchers) : LocationProvider {
    private val manager by lazy { context.getSystemService(LocationManager::class.java) }
    private val callbacks = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")   // checked by the tool loop before this runs (ToolPermissions)
    override suspend fun current(): Outcome<Coordinates> {
        val lm = manager ?: return fail(LocationProvider.NO_FIX)
        if (!LocationManagerCompat.isLocationEnabled(lm)) return fail(LocationProvider.LOCATION_OFF)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { it in lm.allProviders && lm.isProviderEnabled(it) }
        return try {
            // The 10 s are shared between the providers. With coarse permission the fused provider uses network location
            // only, and never switches GPS on: on the emulator (no network location; its test location comes through
            // GPS) it waited the whole time while GPS was never asked.
            val share = TIMEOUT_MS / providers.size.coerceAtLeast(1)
            val fresh = providers.firstNotNullOfOrNull { provider ->
                try {
                    withTimeoutOrNull(share) { currentFrom(lm, provider) }
                } catch (e: SecurityException) {
                    null   // GPS needs fine location before Android 12; try the next one
                }
            }
            val location = fresh ?: providers.mapNotNull { lm.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < MAX_AGE_MS }
                .maxByOrNull { it.time }
            if (location == null) {
                Log.w(LogTag.Tools, "No location fix (providers: ${providers.joinToString()})")   // never the coordinates
                fail(LocationProvider.NO_FIX)
            } else {
                Outcome.Success(Coordinates(location.latitude, location.longitude).rounded())
            }
        } catch (e: SecurityException) {
            fail(LocationProvider.DENIED)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFrom(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        LocationManagerCompat.getCurrentLocation(lm, provider, cancel, callbacks) { location -> if (cont.isActive) cont.resume(location) }
    }

    override suspend fun placeName(at: Coordinates): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(at.latitude, at.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                    })
                }
            } else {
                withContext(dispatchers.io) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(at.latitude, at.longitude, 1)?.firstOrNull() }.getOrNull()
                }
            }
        } ?: return null
        return label(address.locality ?: address.subAdminArea, address.adminArea, address.countryCode)
    }

    private fun fail(message: String) = Outcome.Failure(AppError.ToolFailed("location", message))

    internal companion object {
        const val TIMEOUT_MS = 10_000L
        const val GEOCODE_TIMEOUT_MS = 5_000L
        const val MAX_AGE_MS = 10 * 60_000L

        /** Same shape as weather places: "Fowlerville, Michigan (US)". */
        fun label(place: String?, region: String?, countryCode: String?): String? {
            val name = place ?: region ?: return null
            return listOfNotNull(name, region?.takeIf { it != name }).joinToString(", ") + (countryCode?.let { " ($it)" } ?: "")
        }
    }
}
