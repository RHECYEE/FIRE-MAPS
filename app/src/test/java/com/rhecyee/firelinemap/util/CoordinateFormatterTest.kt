package com.rhecyee.firelinemap.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateFormatterTest {
    @Test
    fun formatsDegreesDecimalMinutes() {
        assertEquals(
            "N 45° 12.345'  W 117° 38.221'",
            CoordinateFormatter.format(45.20575, -117.6370166667, CoordinateFormat.DDM)
        )
    }
}
