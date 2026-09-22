package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.fireline.FirelineKind
import com.rhecyee.firelinemap.fireline.InferredPerimeter
import com.rhecyee.firelinemap.fireline.PerimeterInference
import com.rhecyee.firelinemap.measure.AreaUnit
import com.rhecyee.firelinemap.measure.DistanceUnit
import kotlin.math.roundToInt

/** The colour the fire side of the tool is drawn in, everywhere. */
val FIRE_RED = Color(0xFFE53935)

/** And the clean side. Deliberately nothing like the fire colour. */
val NOT_FIRE_BLUE = Color(0xFF29B6F6)

/**
 * Controls and live readout for the perimeter tool.
 *
 * The acreage is the number that will get read over a radio, so it is the
 * largest thing on the panel -- and directly under it, in the same breath,
 * what it was inferred from. A figure produced from four points and a
 * kilometre of assumption should not be able to look like a surveyed one.
 */
@Composable
fun FirelinePanel(
    perimeter: InferredPerimeter,
    kind: FirelineKind,
    linking: Boolean,
    firePoints: Int,
    cleanPoints: Int,
    reachMeters: Double,
    reachIsAutomatic: Boolean,
    working: Boolean,
    distanceUnit: DistanceUnit,
    areaUnit: AreaUnit,
    onSelectKind: (FirelineKind) -> Unit,
    onToggleLinking: () -> Unit,
    onBreakRun: () -> Unit,
    onReach: (Double) -> Unit,
    onAutoReach: () -> Unit,
    onCycleAreaUnit: () -> Unit,
    onCycleDistanceUnit: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF3A1512), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KindChip(
                label = "FIRE",
                selected = kind == FirelineKind.FIRE,
                accent = FIRE_RED,
                onClick = { onSelectKind(FirelineKind.FIRE) }
            )
            KindChip(
                label = "NOT FIRE",
                selected = kind == FirelineKind.NOT_FIRE,
                accent = NOT_FIRE_BLUE,
                onClick = { onSelectKind(FirelineKind.NOT_FIRE) }
            )
            Spacer(Modifier.weight(1f))
            Action("UNDO", onUndo)
            Action("CLEAR", onClear)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KindChip(
                label = if (linking) "LINE ON" else "POINTS",
                selected = linking,
                accent = Color(0xFFFFC400),
                onClick = { onToggleLinking() }
            )
            if (linking) Action("END RUN", onBreakRun)
            Spacer(Modifier.weight(1f))
            Text(
                "$firePoints fire · $cleanPoints clean",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium
            )
        }

        if (firePoints == 0) {
            Text(
                "Drop FIRE where it is burning, or has burnt. Drop NOT FIRE " +
                    "where you know it is clean. Two of each is enough to start.",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                buildString {
                    append(format(areaUnit.from(perimeter.areaSquareMeters)))
                    append(' ')
                    append(areaUnit.label)
                    if (working) append("  …")
                },
                modifier = Modifier.clickable { onCycleAreaUnit() },
                color = Color(0xFFFF8A65),
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                buildString {
                    append("Perimeter ")
                    append(format(distanceUnit.from(perimeter.perimeterMeters)))
                    append(' ')
                    append(distanceUnit.label)
                    if (perimeter.polygonCount > 1) {
                        append("   ·   ${perimeter.polygonCount} separate bodies")
                    }
                    val holes = perimeter.rings.count { it.isHole }
                    if (holes > 0) append("   ·   $holes island${if (holes > 1) "s" else ""}")
                },
                modifier = Modifier.clickable { onCycleDistanceUnit() },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )

            // The honest caveat, stated every time rather than buried in a
            // help screen. This is an interpolation between observations, and
            // the number above is only worth what the points below it are.
            Text(
                "INFERRED from $firePoints fire and $cleanPoints clean " +
                    "observation${if (firePoints + cleanPoints == 1) "" else "s"} — " +
                    "not a surveyed perimeter",
                color = Color(0xFFFFCC80),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Reach ${reachLabel(reachMeters, distanceUnit)}" +
                    if (reachIsAutomatic) " (auto)" else "",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (!reachIsAutomatic) Action("AUTO", onAutoReach)
            if (!perimeter.isEmpty) Action("SAVE", onSave)
        }

        // How far the fire is allowed to run past a dropped point where
        // nothing says otherwise. There is no honest default for ground
        // nobody has looked at, so it is a control rather than a constant.
        Slider(
            value = reachMeters.toFloat(),
            onValueChange = { onReach(it.toDouble()) },
            valueRange = PerimeterInference.MIN_REACH_METERS.toFloat()..
                PerimeterInference.MAX_REACH_METERS.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = FIRE_RED,
                activeTrackColor = FIRE_RED,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            )
        )
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun KindChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Text(
        label,
        modifier = Modifier
            .background(
                if (selected) accent else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (selected) Color(0xFF140300) else Color.White,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.labelMedium
    )
}

private fun reachLabel(meters: Double, unit: DistanceUnit): String = when (unit) {
    DistanceUnit.FEET, DistanceUnit.METERS ->
        if (meters >= 1_000) "${format(unit.from(meters))} ${unit.label}"
        else "${unit.from(meters).roundToInt()} ${unit.label}"
    else -> "${format(unit.from(meters))} ${unit.label}"
}

private fun format(value: Double): String = when {
    value >= 1_000 -> "%,.0f".format(value)
    value >= 100 -> "%.0f".format(value)
    value >= 10 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}
