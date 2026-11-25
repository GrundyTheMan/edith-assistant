@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.tools.location

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The iPhone's approximate location (K4.2), asked for only when the owner says "here" (C11): Core Location with kilometre accuracy, which is all the
 * weather needs and uses the least battery. The system asks the owner once ("while using the app"); `NSLocationWhenInUseUsageDescription` in
 * `iosApp/project.yml` is the text it shows. The coordinates are never stored or logged and never sent to the model: only the place's name goes in a
 * tool result. Core Location works from the main thread, so every call here moves to it. Written without a Mac to try it on: the macOS jobs compile
 * it, and the owner's phone is the first real run.
 */
class IosLocationProvider : LocationProvider {
    private var manager: CLLocationManager? = null
    private var asked: CompletableDeferred<Unit>? = null
    private var fix: CompletableDeferred<Outcome<Coordinates>>? = null

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) asked?.complete(Unit)
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            val (lat, lon) = location.coordinate.useContents { latitude to longitude }
            fix?.complete(Outcome.Success(Coordinates(lat, lon)))
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            Log.w(LogTag.Tools, "Core Location could not find the phone (error ${didFailWithError.code})")
            fix?.complete(failure(LocationProvider.NO_FIX))
        }
    }

    override suspend fun current(): Outcome<Coordinates> = withContext(Dispatchers.Main) {
        if (!CLLocationManager.locationServicesEnabled()) return@withContext failure(LOCATION_OFF)
        val m = manager ?: CLLocationManager().also { it.delegate = delegate; it.desiredAccuracy = kCLLocationAccuracyKilometer; manager = it }
        if (m.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
            val question = CompletableDeferred<Unit>().also { asked = it }
            m.requestWhenInUseAuthorization()
            question.await()
            asked = null
        }
        val status = m.authorizationStatus
        if (status != kCLAuthorizationStatusAuthorizedWhenInUse && status != kCLAuthorizationStatusAuthorizedAlways) return@withContext failure(LocationProvider.DENIED)
        val result = CompletableDeferred<Outcome<Coordinates>>().also { fix = it }
        m.requestLocation()
        val found = withTimeoutOrNull(FIX_TIMEOUT_MS) { result.await() } ?: failure(LocationProvider.NO_FIX)
        fix = null
        found
    }

    override suspend fun placeName(at: Coordinates): String? = withContext(Dispatchers.Main) {
        val answer = suspendCancellableCoroutine { continuation ->
            CLGeocoder().reverseGeocodeLocation(CLLocation(at.latitude, at.longitude)) { placemarks, _ ->
                val p = placemarks?.firstOrNull() as? platform.CoreLocation.CLPlacemark
                val town = p?.locality ?: p?.subAdministrativeArea
                val name = listOfNotNull(town, p?.administrativeArea).joinToString(", ").ifBlank { null }
                continuation.resume(name?.let { n -> p?.ISOcountryCode?.let { "$n ($it)" } ?: n })
            }
        }
        answer
    }

    private fun failure(detail: String): Outcome<Coordinates> = Outcome.Failure(AppError.ToolFailed("weather", detail))

    private companion object {
        const val FIX_TIMEOUT_MS = 10_000L
        const val LOCATION_OFF = "Location Services are turned off on the iPhone — ask for a city, or say they can be turned on in Settings → Privacy & Security."
    }
}
