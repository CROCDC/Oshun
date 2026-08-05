package com.oshun.gpsbridge.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.oshun.gpsbridge.model.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits a [Fix] for every GPS update from the fused location provider. The caller
 * must hold ACCESS_FINE_LOCATION before collecting.
 */
class LocationSource(context: Context) : FixProvider {

    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission") // permission is checked by the caller / service before collecting
    override fun fixes(intervalMillis: Long): Flow<Fix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val satellites = loc.extras?.getInt("satellites", -1) ?: -1
                trySend(
                    Fix(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        speedMetersPerSecond = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                        bearingDegrees = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
                        altitudeMeters = if (loc.hasAltitude()) loc.altitude else 0.0,
                        satellites = satellites,
                        timeUtcMillis = loc.time,
                    )
                )
            }
        }

        client.requestLocationUpdates(request, callback, null)
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
