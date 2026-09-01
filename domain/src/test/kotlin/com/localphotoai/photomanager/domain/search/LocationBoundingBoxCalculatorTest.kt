package com.localphotoai.photomanager.domain.search

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationBoundingBoxCalculatorTest {

    @Test
    fun `zero radius produces a box centered exactly on the point`() {
        val box = LocationBoundingBoxCalculator.fromPointAndRadiusKm(
            latitude = 12.0,
            longitude = 77.0,
            radiusKm = 0.0,
        )
        assertTrue(abs(box.minLatitude - 12.0) < 1e-9)
        assertTrue(abs(box.maxLatitude - 12.0) < 1e-9)
        assertTrue(abs(box.minLongitude - 77.0) < 1e-9)
        assertTrue(abs(box.maxLongitude - 77.0) < 1e-9)
    }

    @Test
    fun `larger radius produces a larger box`() {
        val small = LocationBoundingBoxCalculator.fromPointAndRadiusKm(0.0, 0.0, radiusKm = 1.0)
        val large = LocationBoundingBoxCalculator.fromPointAndRadiusKm(0.0, 0.0, radiusKm = 10.0)
        val smallSpan = small.maxLatitude - small.minLatitude
        val largeSpan = large.maxLatitude - large.minLatitude
        assertTrue(largeSpan > smallSpan)
    }

    @Test
    fun `longitude span widens the same latitude span shrinks away from the equator`() {
        // At the same radius, longitude degrees-per-km shrinks as |latitude| grows
        // (meridians converge toward the poles), so the box's longitude span should
        // be wider near the equator than at a high latitude for the same radius.
        val equator = LocationBoundingBoxCalculator.fromPointAndRadiusKm(0.0, 0.0, radiusKm = 50.0)
        val highLatitude = LocationBoundingBoxCalculator.fromPointAndRadiusKm(60.0, 0.0, radiusKm = 50.0)
        val equatorLonSpan = equator.maxLongitude - equator.minLongitude
        val highLatLonSpan = highLatitude.maxLongitude - highLatitude.minLongitude
        assertTrue(highLatLonSpan > equatorLonSpan)
    }
}
