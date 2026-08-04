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
        assertTrue(RadioReadout.spoken(report()).startsWith(RadioReadout.STANDBY))
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
    fun elevationIsIncludedForTheAircraft() {
        assertTrue(RadioReadout.spoken(report()).contains("elevation 1417 metres"))
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
        assertTrue(script.startsWith(RadioReadout.STANDBY))
        // Every heading present, in order.
        var cursor = 0
        for (line in RadioReadout.lines(report())) {
            val at = script.indexOf(line.heading, cursor)
            assertTrue("${line.heading} out of order", at >= cursor)
            cursor = at
        }
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
