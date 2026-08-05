package com.rhecyee.firelinemap.medical

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the readout says when an aircraft is coming, and when one is not.
 *
 * The distinction the wording used to lose: when a helicopter cannot land on
 * the patient there are two places and a carry between them. "LZ at these
 * coordinates" leaves whoever is copying it to work out whether that is where
 * the patient is -- and the answer decides whether a ground unit is needed at
 * all, which is not a thing to leave to inference on a medical.
 */
class AirTransportTest {

    private fun report(
        transport: TransportMode = TransportMode.GROUND,
        pickupName: String? = null,
        pickupLatitude: Double? = null,
        pickupLongitude: Double? = null,
        hazards: String? = null
    ) = MedicalReport(
        id = "r",
        incidentId = "i",
        createdAt = 0L,
        incidentName = "Burnt Creek 2026",
        mapName = null,
        latitude = 45.20575,
        longitude = -117.63700,
        elevationMeters = null,
        accuracyMeters = null,
        reporterName = "L. Yee",
        reporterQualification = "EMT",
        transport = transport,
        natureOfInjury = "fall, lower leg",
        patientAssessment = "alert, stable",
        lzHazards = hazards,
        airPickupName = pickupName,
        airPickupLatitude = pickupLatitude,
        airPickupLongitude = pickupLongitude
    )

    private fun transportLine(report: MedicalReport): String =
        RadioReadout.lines(report).first { it.number == 4 }.body

    // ------------------------------------------------------------- ground

    /**
     * A ground request has no landing zone, so it must not ask about one.
     *
     * A line reading "LZ hazards: none reported" on a call where nothing is
     * landing reads as an omission somebody should go and fill in.
     */
    @Test
    fun aGroundRequestSaysNothingAboutALandingZone() {
        val said = transportLine(report(TransportMode.GROUND))
        assertFalse(said, said.contains("LZ"))
        assertFalse(said, said.contains("landing zone"))
        assertTrue(said, said.contains("Pickup"))
    }

    @Test
    fun aGroundRequestDoesNotCountLzHazardsAsMissing() {
        assertFalse(report(TransportMode.GROUND).missing.contains("LZ hazards"))
        // And an air one does.
        assertTrue(report(TransportMode.AIR).missing.contains("LZ hazards"))
    }

    @Test
    fun theSpokenVersionAlsoLeavesTheLandingZoneOutOfAGroundCall() {
        val spoken = RadioReadout.spoken(report(TransportMode.GROUND, hazards = "wires"))
        assertFalse(spoken, spoken.contains("LZ hazards"))
    }

    // ---------------------------------------------------------------- air

    @Test
    fun anAircraftLandingOnThePatientSaysSo() {
        val said = transportLine(report(TransportMode.AIR, hazards = "wires north"))
        assertTrue(said, said.contains("Aircraft lands at the patient"))
        assertTrue(said, said.contains("LZ hazards: wires north"))
    }

    /**
     * The case this was built for: the patient is carried to a helispot.
     */
    @Test
    fun aSeparateHelispotIsNamedAndTheCarryIsStated() {
        val said = transportLine(
            report(TransportMode.AIR, pickupName = "H-3", hazards = "snags east")
        )
        assertTrue(said, said.contains("Patient at"))
        // Named, not given as a coordinate: a helispot has a name on the IAP
        // and that is what goes over the radio.
        assertTrue(said, said.contains("Ground to H-3 for air pickup"))
        assertTrue(said, said.contains("LZ hazards: snags east"))
    }

    @Test
    fun anLzDroppedAsAPinIsGivenAsAPositionToo() {
        val said = transportLine(
            report(
                TransportMode.AIR,
                pickupName = "Helispot 3",
                pickupLatitude = 45.21000,
                pickupLongitude = -117.64000,
                hazards = "none"
            )
        )
        assertTrue(said, said.contains("Ground to Helispot 3 at N 45"))
    }

    @Test
    fun anUnnamedLandingZoneStillReadsAsOne() {
        val said = transportLine(
            report(
                TransportMode.AIR,
                pickupLatitude = 45.21000,
                pickupLongitude = -117.64000,
                hazards = "none"
            )
        )
        assertTrue(said, said.contains("Ground to the landing zone at N 45"))
    }

    @Test
    fun theSpokenVersionCarriesTheGroundLegToo() {
        val spoken = RadioReadout.spoken(
            report(TransportMode.AIR, pickupName = "H-3", hazards = "wires")
        )
        assertTrue(spoken, spoken.contains("Patient at"))
        assertTrue(spoken, spoken.contains("Ground to H-3 for air pickup"))
        assertTrue(spoken, spoken.contains("LZ hazards: wires"))
    }

    @Test
    fun airAndGroundTogetherIsStillAnAirRequestForThesePurposes() {
        assertTrue(TransportMode.BOTH.needsAir)
        assertTrue(TransportMode.AIR.needsAir)
        assertFalse(TransportMode.GROUND.needsAir)

        val said = transportLine(report(TransportMode.BOTH, pickupName = "H-3"))
        assertTrue(said, said.contains("Ground to H-3"))
    }

    @Test
    fun anAirRequestWithNowhereToLandSaysWhatIsMissing() {
        val nowhere = report(TransportMode.AIR).copy(hasPosition = false)
        assertTrue(nowhere.missing.toString(), nowhere.missing.contains("a landing zone"))
    }

    /** Both formats have to agree; a crew running either hears the same plan. */
    @Test
    fun bothFormatsCarryTheSameTransportPlan()  {
        val air = report(TransportMode.AIR, pickupName = "H-3", hazards = "wires")
        val mir = transportLine(air.copy(format = ReportFormat.MIR))
        val eight = transportLine(air.copy(format = ReportFormat.EIGHT_LINE))
        org.junit.Assert.assertEquals(mir, eight)
    }
}
