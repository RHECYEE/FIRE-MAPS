package com.rhecyee.firelinemap.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhecyee.firelinemap.fireline.FirelineKind
import com.rhecyee.firelinemap.fireline.InferredPerimeter
import com.rhecyee.firelinemap.location.LiveTrack
import com.rhecyee.firelinemap.measure.AreaUnit
import com.rhecyee.firelinemap.measure.DistanceUnit
import com.rhecyee.firelinemap.measure.MeasureMode
import com.rhecyee.firelinemap.measure.MeasureSession
import com.rhecyee.firelinemap.resources.ResourceSymbol
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The controls that decide which mode a tap lands in.
 *
 * The line and polygon switch was previously the mode's own label, which had
 * to be tapped to discover it was a control. Asserting both choices are on
 * screen keeps that from quietly regressing.
 */
@RunWith(AndroidJUnit4::class)
class ToolPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun emptyResult(mode: MeasureMode) = MeasureSession(mode).result()

    @Test
    fun bothMeasureModesAreOfferedAsControls() {
        var chosen: MeasureMode? = null
        compose.setContent {
            MeasurePanel(
                result = emptyResult(MeasureMode.DISTANCE),
                mode = MeasureMode.DISTANCE,
                distanceUnit = DistanceUnit.FEET,
                areaUnit = AreaUnit.ACRES,
                elevationPending = false,
                onCycleDistanceUnit = {},
                onCycleAreaUnit = {},
                onSelectMode = { chosen = it },
                onUndo = {},
                onClear = {}
            )
        }

        compose.onNodeWithText("LINE").assertIsDisplayed()
        compose.onNodeWithText("POLYGON").assertIsDisplayed()

        compose.onNodeWithText("POLYGON").performClick()
        assertEquals(MeasureMode.AREA, chosen)

        compose.onNodeWithText("LINE").performClick()
        assertEquals(MeasureMode.DISTANCE, chosen)
    }

    @Test
    fun undoAndClearAreReachable() {
        var undone = false
        var cleared = false
        compose.setContent {
            MeasurePanel(
                result = emptyResult(MeasureMode.DISTANCE),
                mode = MeasureMode.DISTANCE,
                distanceUnit = DistanceUnit.FEET,
                areaUnit = AreaUnit.ACRES,
                elevationPending = false,
                onCycleDistanceUnit = {},
                onCycleAreaUnit = {},
                onSelectMode = {},
                onUndo = { undone = true },
                onClear = { cleared = true }
            )
        }

        compose.onNodeWithText("UNDO").performClick()
        compose.onNodeWithText("CLEAR").performClick()
        assertEquals(true, undone)
        assertEquals(true, cleared)
    }

    @Test
    fun theResourcePaletteOffersItsSymbols() {
        var picked: ResourceSymbol? = null
        compose.setContent {
            ResourcePalette(
                symbols = ResourceSymbol.RESOURCES,
                selected = null,
                onSelect = { picked = it }
            )
        }

        compose.onNodeWithText("Engine").performClick()
        assertEquals(ResourceSymbol.ENGINE, picked)
    }

    @Test
    fun thePointPaletteIsSeparateFromResources() {
        var picked: ResourceSymbol? = null
        compose.setContent {
            ResourcePalette(
                symbols = ResourceSymbol.POINTS,
                selected = null,
                onSelect = { picked = it }
            )
        }

        // A hazard is a mark on the ground, not an assigned resource.
        compose.onNodeWithText("Hazard").performClick()
        assertEquals(ResourceSymbol.HAZARD, picked)
    }

    /**
     * The one control on the perimeter tool that must never be ambiguous.
     *
     * Everything the tool produces rests on which of the two kinds a tap
     * drops. Wired backwards, it would report clean ground as fire and the
     * polygon would still look perfectly plausible, so both choices being on
     * screen and reporting themselves correctly is worth asserting rather
     * than assuming.
     */
    @Test
    fun theFirelineToolOffersFireAndNotFireAsSeparateChoices() {
        var chosen: FirelineKind? = null
        compose.setContent {
            FirelinePanel(
                perimeter = InferredPerimeter.empty(),
                kind = FirelineKind.FIRE,
                linking = false,
                firePoints = 0,
                cleanPoints = 0,
                reachMeters = 300.0,
                reachIsAutomatic = true,
                working = false,
                distanceUnit = DistanceUnit.FEET,
                areaUnit = AreaUnit.ACRES,
                onSelectKind = { chosen = it },
                onToggleLinking = {},
                onBreakRun = {},
                onReach = {},
                onAutoReach = {},
                onCycleAreaUnit = {},
                onCycleDistanceUnit = {},
                onUndo = {},
                onClear = {},
                onSave = {}
            )
        }

        compose.onNodeWithText("FIRE").assertIsDisplayed()
        compose.onNodeWithText("NOT FIRE").assertIsDisplayed()

        compose.onNodeWithText("NOT FIRE").performClick()
        assertEquals(FirelineKind.NOT_FIRE, chosen)

        compose.onNodeWithText("FIRE").performClick()
        assertEquals(FirelineKind.FIRE, chosen)
    }

    /**
     * An acreage with nothing said about where it came from is the failure
     * mode this tool has to avoid. It is an inference, and it has to read as
     * one on the same screen as the number.
     */
    @Test
    fun theInferredAcreageSaysWhatItWasInferredFrom() {
        compose.setContent {
            FirelinePanel(
                perimeter = InferredPerimeter.empty(300.0),
                kind = FirelineKind.FIRE,
                linking = false,
                firePoints = 4,
                cleanPoints = 2,
                reachMeters = 300.0,
                reachIsAutomatic = true,
                working = false,
                distanceUnit = DistanceUnit.FEET,
                areaUnit = AreaUnit.ACRES,
                onSelectKind = {},
                onToggleLinking = {},
                onBreakRun = {},
                onReach = {},
                onAutoReach = {},
                onCycleAreaUnit = {},
                onCycleDistanceUnit = {},
                onUndo = {},
                onClear = {},
                onSave = {}
            )
        }

        compose.onNodeWithText("4 fire · 2 clean").assertIsDisplayed()
        compose.onNodeWithText(
            "INFERRED from 4 fire and 2 clean observations — not a surveyed perimeter"
        ).assertIsDisplayed()
    }

    @Test
    fun armedButNotYetMovingLooksDifferentFromNotWorking() {
        compose.setContent {
            TravelPanel(live = LiveTrack(), armed = true, unit = DistanceUnit.MILES)
        }
        compose.onNodeWithText("WATCHING FOR TRAVEL — start moving").assertIsDisplayed()
    }

    @Test
    fun aRecordingTrackShowsItsFigures() {
        compose.setContent {
            TravelPanel(
                live = LiveTrack(
                    recording = true,
                    startedAt = 0L,
                    lastFixAt = 3_600_000L,
                    distanceMeters = 8_046.72,
                    movingMillis = 3_000_000L,
                    points = List(120) { 45.0 to -117.0 }
                ),
                armed = true,
                unit = DistanceUnit.MILES
            )
        }

        compose.onNodeWithText("TRAVEL RECORDING").assertIsDisplayed()
        compose.onNodeWithText("01:00:00").assertIsDisplayed()
        compose.onNodeWithText("5.00 mi").assertIsDisplayed()
        compose.onNodeWithText("120").assertIsDisplayed()
    }

    @Test
    fun aPausedTrackSaysSoRatherThanLookingStopped() {
        compose.setContent {
            TravelPanel(
                live = LiveTrack(
                    recording = true,
                    paused = true,
                    startedAt = 0L,
                    lastFixAt = 600_000L,
                    distanceMeters = 1_000.0
                ),
                armed = true,
                unit = DistanceUnit.MILES
            )
        }
        compose.onNodeWithText("TRAVEL PAUSED").assertIsDisplayed()
    }
}
