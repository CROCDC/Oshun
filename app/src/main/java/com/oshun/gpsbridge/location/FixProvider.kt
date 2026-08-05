package com.oshun.gpsbridge.location

import com.oshun.gpsbridge.model.Fix
import kotlinx.coroutines.flow.Flow

/**
 * Source of GPS fixes. Abstracted so the foreground service can be unit-tested
 * with a fake provider instead of the real (Play Services) [LocationSource].
 */
interface FixProvider {
    fun fixes(intervalMillis: Long): Flow<Fix>
}
