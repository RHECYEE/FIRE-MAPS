package com.rhecyee.firelinemap.medical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioReadoutTest {

    private fun report(
        priority: Priority = Priority.RED,
        transport: TransportMode = TransportMode.AIR,
        resources: Set<MedicalResource> = setOf(MedicalResource.HOIST),
        patientCount: Int = 1,
        format: ReportFormat = ReportFormat.MIR,
        updates: List<ReportUpdate> = emptyList(),
        natureOfInjury: String? = "struck by snag",
        lzHazards: String? = "timber and smoke",
        assessment: String? = "conscious, breathing, leg deformity"
    ) = MedicalReport(
        id = "r1",
        incidentId = "i1",
        createdAt = 0L,
        incidentName = "Burnt Creek",
        mapName = "ops_arch_c_20260728.pdf",
        latitude = 45.719620,
        longitude = -117.267328,
        elevationMeters = 1417.0,
        accuracyMeters = 4f,
        reporterName = "Luke Yee",
        reporterQualification = "EMT",
        priority = priority,
        patientCount = patientCount,
        transport = transport,
        resources = resources,
        natureOfInjury = natureOfInjury,
        patientAssessment = assessment,
        lzHazards = lzHazards,
        incidentCommander = "Luke Yee",
        medicalProvider = "REMS 2",
        trackId = "t1",
        photoCount = 2,
        updates = updates,
        format = format
    )

    @Test
    fun theSpokenReadoutOpensWithEmergencyTraffic() {
        val spoken = RadioReadout.spoken(report())
        assertTrue(spoken, spoken.startsWith("Communications, Burnt Creek Medical, stand by"))
        assertTrue(spoken, spoken.contains("emergency traffic"))
    }

    /**
     * The identifier goes in two places and only two.
     *
     * On the call and on the handback -- whoever answers has to know who they
     * are answering before writing anything down, and who to call back when
     * the traffic ends. It was landing three times, once as a bare fragment
     * dropped between the patient count and the position, which read as a
     * stumble in the middle of the transmission.
     */
    @Test
    fun theNameIsSaidOnTheCallAndOnTheHandbackAndNowhereElse() {
        val spoken = RadioReadout.spoken(report())
        assertEquals(spoken, 2, spoken.split("Burnt Creek Medical").size - 1)
        assertTrue(spoken, spoken.startsWith("Communications, Burnt Creek Medical"))
        assertTrue(spoken, spoken.endsWith("Burnt Creek Medical, how copy?"))

        val script = RadioReadout.script(report())
        assertEquals(script, 2, script.split("Burnt Creek Medical").size - 1)
    }

    /**
     * The paragraph and the numbered line have to agree about the landing zone.
     *
     * They did not. Line 4 gave the position of an LZ dropped as a pin and the
     * spoken version left it out, so an unnamed one came out as "ground to the
     * landing zone" with nothing to fly to -- in the version that gets read
     * aloud while walking.
     */
    @Test
    fun theSpokenVersionGivesTheLandingZonePositionToo() {
        val dropped = report().copy(
            transport = TransportMode.AIR,
            airPickupName = null,
            airPickupLatitude = 45.21000,
            airPickupLongitude = -117.64000
        )
        val spoken = RadioReadout.spoken(dropped)
        assertTrue(spoken, spoken.contains("Ground to the landing zone at N 45"))

        // And the two say the same words, because they come from one place.
        val line4 = RadioReadout.lines(dropped).first { it.number == 4 }.body
        assertTrue(spoken, spoken.contains(line4))
    }

    @Test
    fun theSpokenReadoutCarriesEverythingNeededToRespond() {
        val spoken = RadioReadout.spoken(report())

        assertTrue(spoken.contains("Red priority"))
        assertTrue(spoken.contains("one patient"))
        assertTrue(spoken.contains("struck by snag"))
        assertTrue(spoken.contains("Burnt Creek Medical"))
        assertTrue(spoken.contains("IC Luke Yee"))
        assertTrue(spoken.contains("Patient care REMS 2"))
        assertTrue(spoken.contains("air ambulance"))
        assertTrue(spoken.contains("hoist capability"))
        assertTrue(spoken.contains("LZ hazards: timber and smoke"))
    }

    @Test
    fun coordinatesAreSpokenInDegreesAndDecimalMinutes() {
        // What aircraft work in, and the air ambulance is the one writing it
        // down at speed.
        val spoken = RadioReadout.spoken(report())
        assertTrue("was: $spoken", spoken.contains("N 45° 43.177'"))
        assertTrue("was: $spoken", spoken.contains("W 117° 16.040'"))
    }

    @Test
    fun elevationIsNotReadOut() {
        // Dropped on request: it lengthens the transmission without helping
        // anyone find the patient.
        val script = RadioReadout.script(report())
        assertFalse(script.contains("1417"))
        assertFalse(script.lowercase().contains("elevation"))
    }

    @Test
    fun theRadioNameDropsTheYearFromTheIncident() {
        // "Burnt Creek 2026 Medical" is a mouthful; nobody says the year.
        val named = report().copy(incidentName = "Burnt Creek 2026")
        assertEquals("Burnt Creek", named.radioName)
        assertTrue(RadioReadout.spoken(named).contains("Burnt Creek Medical"))
        assertFalse(RadioReadout.spoken(named).contains("2026"))
    }

    @Test
    fun anOverriddenRadioNameWins() {
        val named = report().copy(
            incidentName = "Burnt Creek 2026",
            radioNameOverride = "Chico Creek"
        )
        assertEquals("Chico Creek", named.radioName)
        assertTrue(RadioReadout.spoken(named).contains("Chico Creek Medical"))
    }

    @Test
    fun aNameWithNoYearIsLeftAlone() {
        assertEquals(
            "Burnt Creek",
            report().copy(incidentName = "Burnt Creek").radioName
        )
    }

    @Test
    fun severalResourcesAreReadAsAList() {
        val spoken = RadioReadout.spoken(
            report(
                resources = setOf(
                    MedicalResource.HOIST, MedicalResource.LITTER, MedicalResource.ALS
                )
            )
        )
        assertTrue("was: $spoken", spoken.contains("and advanced life support"))
        assertTrue(spoken.contains(","))
    }

    @Test
    fun aGroundOnlyRequestDoesNotAskForAnAircraft() {
        val spoken = RadioReadout.spoken(
            report(transport = TransportMode.GROUND, resources = emptySet())
        )
        assertTrue(spoken.contains("ground ambulance"))
        assertFalse(spoken.contains("air ambulance"))
    }

    @Test
    fun multiplePatientsAreSpelledOut() {
        assertTrue(RadioReadout.spoken(report(patientCount = 3)).contains("three patients"))
        assertTrue(RadioReadout.spoken(report(patientCount = 1)).contains("one patient"))
    }

    @Test
    fun bothFormsProduceEightNumberedLines() {
        assertEquals(8, RadioReadout.lines(report(format = ReportFormat.EIGHT_LINE)).size)
        assertEquals(8, RadioReadout.lines(report(format = ReportFormat.MIR)).size)
    }

    @Test
    fun theLinesAreNumberedInOrder() {
        RadioReadout.lines(report()).forEachIndexed { index, line ->
            assertEquals(index + 1, line.number)
        }
    }

    @Test
    fun theSameCaptureDrivesEitherForm() {
        val captured = report()
        val mir = RadioReadout.script(captured.copy(format = ReportFormat.MIR))
        val eightLine = RadioReadout.script(captured.copy(format = ReportFormat.EIGHT_LINE))

        // Different headings, same facts.
        assertTrue(mir.contains("Patient Assessment"))
        assertTrue(eightLine.contains("Initial Patient Assessment"))
        for (fact in listOf("Burnt Creek", "REMS 2", "N 45° 43.177'", "timber and smoke")) {
            assertTrue("MIR missing $fact", mir.contains(fact))
            assertTrue("8-line missing $fact", eightLine.contains(fact))
        }
    }

    @Test
    fun noResourcesReadsAsNoneRatherThanBlank() {
        val line = RadioReadout.lines(report(resources = emptySet()))
            .first { it.heading.contains("Resources") }
        assertEquals("None.", line.body)
    }

    @Test
    fun absentHazardsAreStatedRatherThanOmitted() {
        // Silence on the radio reads as "not checked"; this says it was.
        val line = RadioReadout.lines(report(lzHazards = null))
            .first { it.heading.contains("Transport") }
        assertTrue("was: ${line.body}", line.body.contains("LZ hazards: none reported"))
    }

    @Test
    fun documentationCarriesWhoReportedItAndFromWhichMap() {
        val line = RadioReadout.lines(report()).first { it.heading == "Documentation" }
        assertTrue(line.body.contains("Luke Yee"))
        assertTrue(line.body.contains("EMT"))
        assertTrue(line.body.contains("ops_arch_c_20260728.pdf"))
        assertTrue(line.body.contains("4 m"))
        assertTrue(line.body.contains("2 photos"))
        assertTrue(line.body.contains("Travel track attached"))
    }

    @Test
    fun updatesAppearInTheirOwnLine() {
        val withUpdates = report(
            updates = listOf(
                ReportUpdate(1000L, "Patient packaged"),
                ReportUpdate(2000L, "Moving to LZ")
            )
        )
        val line = RadioReadout.lines(withUpdates).first { it.heading == "Updates" }
        assertTrue(line.body.contains("Patient packaged."))
        assertTrue(line.body.contains("Moving to LZ."))
    }

    @Test
    fun aReportIsNotReadyUntilTheSpokenFieldsAreIn() {
        val bare = report(natureOfInjury = null, assessment = null, lzHazards = null)
        assertFalse(bare.isReadyToTransmit)
        assertTrue(bare.missing.contains("nature of injury"))
        assertTrue(bare.missing.contains("patient assessment"))
        // Air transport was requested, so hazards matter.
        assertTrue(bare.missing.contains("LZ hazards"))

        assertTrue(report().isReadyToTransmit)
    }

    @Test
    fun aGroundRequestDoesNotDemandLzHazards() {
        val ground = report(transport = TransportMode.GROUND, lzHazards = null)
        assertFalse(ground.missing.contains("LZ hazards"))
    }

    @Test
    fun theScriptIsReadableTopToBottom() {
        val script = RadioReadout.script(report())
        // Line 1 carries the standby call; the script does not print it twice.
        assertTrue(script, script.startsWith("1. Contact Communications"))
        assertEquals(1, script.split("stand by for emergency traffic").size - 1)

        // Every heading that has something to say, in order.
        var cursor = 0
        for (line in RadioReadout.lines(report()).filter { it.spokenNow }) {
            val at = script.indexOf(line.heading, cursor)
            assertTrue("${line.heading} out of order", at >= cursor)
            cursor = at
        }
    }

    /**
     * How it ends.
     *
     * The empty follow-up slots used to be read out as "None yet." and "No
     * changes reported." -- two lines of nothing at the close of an emergency
     * transmission, and no handoff, so the other station is left waiting for
     * more that is not coming.
     */
    @Test
    fun theCallEndsByHandingTheChannelBack() {
        val script = RadioReadout.script(report())
        assertTrue(script, script.trimEnd().endsWith("Medical, how copy?"))
        assertTrue(RadioReadout.spoken(report()).trimEnd().endsWith("Medical, how copy?"))
    }

    @Test
    fun theEmptyFollowUpSlotsAreNotReadOut() {
        val script = RadioReadout.script(report())
        assertFalse(script, script.contains("None yet"))
        assertFalse(script, script.contains("No changes reported"))
        assertFalse(script, script.contains("Nothing yet"))
    }

    /** But the form still shows them, because the numbering is the point. */
    @Test
    fun theFormStillCarriesAllEightLines() {
        val lines = RadioReadout.lines(report())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), lines.map { it.number })
        assertTrue(lines.first { it.number == 7 }.body.contains("Nothing yet"))
        assertFalse(lines.first { it.number == 7 }.spokenNow)
    }

    @Test
    fun aFilledFollowUpLineIsReadOut() {
        val moved = report().copy(notes = "Patient loaded, en route to Grande Ronde")
        val script = RadioReadout.script(moved)
        assertTrue(script, script.contains("Patient loaded, en route to Grande Ronde"))
    }

    /**
     * The position is read as two halves, not as one run-on.
     *
     * The formatter pads them apart so a column of coordinates lines up on
     * screen; spoken, that gap is a stumble in the middle of the one thing
     * that has to be copied exactly.
     */
    @Test
    fun theSpokenPositionSeparatesLatitudeFromLongitude() {
        val spoken = RadioReadout.spoken(report())
        assertFalse(spoken, spoken.contains("'  "))
        assertTrue(spoken, spoken.contains("', W "))
    }

    @Test
    fun theNatureOfTheInjuryIsSeparatedFromTheCount() {
        // "one patient fall, lower leg" ran the two together.
        val spoken = RadioReadout.spoken(report())
        assertFalse(spoken, spoken.contains("patient fall"))
        assertTrue(spoken, spoken.contains("one patient, "))
    }
}

/** The report has to survive being written down and read back. */
class MedicalReportPersistenceTest {

    private val report = MedicalReport(
        id = "r1", incidentId = "i1", createdAt = 1234L,
        incidentName = "Burnt Creek", mapName = "ops.pdf",
        latitude = 45.719620, longitude = -117.267328,
        elevationMeters = 1417.0, accuracyMeters = 4f,
        reporterName = "Luke Yee", reporterQualification = "EMT",
        priority = Priority.YELLOW, patientCount = 2,
        transport = TransportMode.BOTH,
        resources = setOf(MedicalResource.HOIST, MedicalResource.ALS),
        natureOfInjury = "struck by snag",
        patientAssessment = "conscious",
        lzHazards = "timber",
        incidentCommander = "Luke Yee", medicalProvider = "REMS 2",
        trackId = "t1", photoCount = 3, format = ReportFormat.EIGHT_LINE
    )

    @Test
    fun everyFieldRoundTripsThroughTheDatabaseShape() {
        val restored = report.toEntity().toReport()

        assertEquals(report.priority, restored.priority)
        assertEquals(report.transport, restored.transport)
        assertEquals(report.resources, restored.resources)
        assertEquals(report.format, restored.format)
        assertEquals(report.patientCount, restored.patientCount)
        assertEquals(report.latitude, restored.latitude, 1e-9)
        assertEquals(report.longitude, restored.longitude, 1e-9)
        assertEquals(report.natureOfInjury, restored.natureOfInjury)
        assertEquals(report.reporterQualification, restored.reporterQualification)
        assertEquals(report.trackId, restored.trackId)
    }

    @Test
    fun anEmptyResourceSetRoundTripsAsEmpty() {
        val restored = report.copy(resources = emptySet()).toEntity().toReport()
        assertTrue(restored.resources.isEmpty())
    }

    @Test
    fun unknownStoredValuesFallBackRatherThanCrashing() {
        // A report written by a newer build must not take the app down.
        val entity = report.toEntity().copy(
            priority = "CHARTREUSE", transport = "TELEPORT", resources = "HOIST,JETPACK"
        )
        val restored = entity.toReport()
        assertEquals(Priority.RED, restored.priority)
        assertEquals(TransportMode.GROUND, restored.transport)
        assertEquals(setOf(MedicalResource.HOIST), restored.resources)
    }
}

/** The form has to open before the receiver is ready. */
class MedicalWithoutPositionTest {

    private val noFix = MedicalReport(
        id = "r1", incidentId = "i1", createdAt = 0L,
        incidentName = "Incident Aug 4", mapName = null,
        latitude = 0.0, longitude = 0.0, hasPosition = false,
        elevationMeters = null, accuracyMeters = null,
        reporterName = null, reporterQualification = null,
        natureOfInjury = "fall", patientAssessment = "conscious",
        transport = TransportMode.GROUND
    )

    @Test
    fun aReportWithNoFixNeverReadsOutACoordinate() {
        // Zero, zero is in the Atlantic. It must not be spoken as a position.
        val spoken = RadioReadout.spoken(noFix)
        assertTrue("was: $spoken", spoken.contains("POSITION NOT YET FIXED"))
        assertFalse(spoken.contains("N 0°"))
    }

    @Test
    fun aReportWithNoFixIsNotReadyToTransmit() {
        assertFalse(noFix.isReadyToTransmit)
        assertTrue(noFix.missing.contains("a position fix"))
    }

    @Test
    fun theSameReportBecomesReadyOnceAFixArrives() {
        val fixed = noFix.copy(
            latitude = 45.719620, longitude = -117.267328, hasPosition = true
        )
        assertTrue(fixed.isReadyToTransmit)
        assertTrue(RadioReadout.spoken(fixed).contains("N 45° 43.177'"))
    }

    @Test
    fun aReportStillWorksWithNoMapAndNoReporter() {
        val line = RadioReadout.lines(noFix).first { it.heading == "Documentation" }
        assertTrue(line.body.isNotBlank())
        assertEquals(8, RadioReadout.lines(noFix).size)
    }
}
