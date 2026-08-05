package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.location.OverlapReport
import com.rhecyee.firelinemap.location.TrackPass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every pass over one spot, read together.
 *
 * The question is travel time. A road driven five times is five answers to how
 * long it takes, and the useful number is not any one of them -- it is what
 * the road actually runs at, which only appears once they are read together.
 * Division supervisors ask this at every briefing and the honest answer has
 * always been a guess.
 *
 * Nothing is merged away. Each pass stays its own entry, because a pass on a
 * Tuesday in a tender and a pass on a Thursday in a buggy are two facts, and
 * deciding which to discard is not this app's call.
 */
@Composable
fun TrackOverlapSheet(
    report: OverlapReport,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tracks through here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            report.describe(),
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleMedium
                        )
                        // The average leaves stops out, and says so, because a
                        // number in a briefing that quietly included a lunch
                        // break is worse than no number.
                        if (report.stoppedCount > 0) {
                            Text(
                                "${report.stoppedCount} of ${report.passes.size} were " +
                                    "stopped here and are left out of the average.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (report.passes.any { it.speedMetersPerSecond == null }) {
                            Text(
                                "Some passes carry no times — recorded before times " +
                                    "were kept, or imported without them.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(report.passes, key = { it.trackId }) { pass -> PassRow(pass) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
private fun PassRow(pass: TrackPass) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(pass.trackName, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        pass.atMillis?.let { whenItWas(it) },
                        "${pass.closestMeters.toInt()} m off"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                when {
                    pass.stopped -> "stopped"
                    pass.speedMetersPerSecond != null ->
                        OverlapReport.formatSpeed(pass.speedMetersPerSecond!!)
                    else -> "no times"
                },
                fontWeight = FontWeight.Black,
                color = when {
                    pass.stopped -> Color(0xFFFFB74D)
                    pass.speedMetersPerSecond != null -> Color(0xFF69F0AE)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private fun whenItWas(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(millis))
