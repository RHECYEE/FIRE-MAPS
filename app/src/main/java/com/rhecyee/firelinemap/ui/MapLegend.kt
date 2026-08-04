package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One entry in the key. */
private data class LegendEntry(
    val colour: Color,
    val label: String,
    val round: Boolean = false
)

/**
 * What the colours on the map mean.
 *
 * Enough things are drawn now -- two kinds of track, three kinds of position,
 * parcels, drop points, a search region -- that the colours stopped being
 * self-explanatory. Shown only while the controls are up, and only for the
 * things actually on screen: a key listing layers nobody has loaded is just
 * more to read.
 */
@Composable
fun MapLegend(
    hasTrack: Boolean,
    hasSavedTracks: Boolean,
    hasParcels: Boolean,
    contourInterval: String?,
    hasDropPoints: Boolean,
    hasSearch: Boolean,
    simulated: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = buildList {
        add(
            LegendEntry(
                if (simulated) Color(0xFFE65100) else Color(0xFF1565C0),
                if (simulated) "Simulated position" else "You",
                round = true
            )
        )
        if (hasTrack) add(LegendEntry(Color(0xFFE91E63), "Recording now"))
        if (hasSavedTracks) add(LegendEntry(Color(0xFF9C27B0), "Saved track"))
        add(LegendEntry(Color(0xFFFFC400), "Measurement"))
        if (hasSearch) add(LegendEntry(Color(0xFF40C4FF), "Search area"))
        if (hasDropPoints) add(LegendEntry(Color(0xFF00E5FF), "Drop point read off sheet", true))
        if (hasParcels) add(LegendEntry(Color(0xFF8D6E63), "Property boundary"))
        add(LegendEntry(Color(0xFFD50000), "Medical", round = true))
        add(LegendEntry(Color(0xFFB3261E), "Off this sheet"))
    }

    Column(
        modifier = modifier
            .width(196.dp)
            .background(Color(0xFF10161B).copy(alpha = 0.90f), RoundedCornerShape(9.dp))
            .clickable { onDismiss() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            "KEY",
            color = Color.White.copy(alpha = 0.65f),
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelSmall
        )
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .size(if (entry.round) 12.dp else 16.dp, 12.dp)
                        .background(
                            entry.colour,
                            if (entry.round) CircleShape else RoundedCornerShape(2.dp)
                        )
                ) {}
                Text(
                    entry.label,
                    modifier = Modifier.padding(start = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (contourInterval != null) {
            // Spelled out rather than shown as a swatch. A contour's colour is
            // not what needs explaining -- how far apart the lines are is, and
            // it changes as the operator zooms, so it has to be read off the
            // key rather than remembered.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .size(16.dp, 12.dp)
                        .background(Color(0xFF9A6634), RoundedCornerShape(2.dp))
                ) {}
                Text(
                    "Contours",
                    modifier = Modifier.padding(start = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                contourInterval,
                modifier = Modifier.padding(start = 24.dp),
                color = Color(0xFFE0B07A),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Text(
            "tap to hide",
            color = Color.White.copy(alpha = 0.4f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
