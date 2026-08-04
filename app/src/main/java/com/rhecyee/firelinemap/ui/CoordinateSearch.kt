package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhecyee.firelinemap.map.MapCoverage
import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter
import com.rhecyee.firelinemap.util.CoordinateParseResult
import com.rhecyee.firelinemap.util.ParsedCoordinate
import com.rhecyee.firelinemap.util.SearchShape

/**
 * A search that stays up while a position is being pieced together.
 *
 * It does not close on a result. A coordinate coming over the radio arrives in
 * fragments and gets corrected, so the query stays editable and the highlight
 * on the map narrows as digits land. Clearing it is a deliberate act.
 */
@Composable
fun CoordinateSearchPanel(
    query: String,
    result: CoordinateParseResult,
    onQueryChange: (String) -> Unit,
    onKeep: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coordinate = (result as? CoordinateParseResult.Success)?.coordinate

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF14232E), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SEARCH",
                color = Color(0xFF4FC3F7),
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (coordinate != null) {
                Text(
                    "KEEP",
                    modifier = Modifier
                        .clickable { onKeep() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(
                "CLEAR",
                modifier = Modifier
                    .clickable { onClear() }
                    .padding(vertical = 6.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                color = Color.White
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            placeholder = {
                Text(
                    "45 43.177 117 16.040",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.4f)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            )
        )

        when {
            coordinate != null -> Result(coordinate)
            result is CoordinateParseResult.Invalid -> Text(
                result.message,
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFF8A80),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
            else -> Text(
                (result as CoordinateParseResult.Incomplete).message +
                    " · X for a digit you missed",
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Result(coordinate: ParsedCoordinate) {
    Text(
        CoordinateFormatter.format(
            coordinate.latitude, coordinate.longitude, CoordinateFormat.DDM
        ),
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF69F0AE),
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium
    )

    val notes = buildList {
        add(coordinate.format.label)
        if (coordinate.assumedHemisphere) add("assumed N and W")
        if (coordinate.inferredDecimal) add("decimal inferred")
    }
    Text(
        when (coordinate.shape) {
            SearchShape.POINT -> "Exact · ${notes.joinToString(" · ")}"
            SearchShape.LINE -> "Somewhere on this line — ${uncertainty(coordinate)}"
            SearchShape.AREA -> "Somewhere in this box — ${uncertainty(coordinate)}"
        },
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall
    )
}

/** How wide the remaining uncertainty is on the ground. */
private fun uncertainty(coordinate: ParsedCoordinate): String {
    val northSouth = MapCoverage.distanceMeters(
        coordinate.southLatitude, coordinate.longitude,
        coordinate.northLatitude, coordinate.longitude
    )
    val eastWest = MapCoverage.distanceMeters(
        coordinate.latitude, coordinate.westLongitude,
        coordinate.latitude, coordinate.eastLongitude
    )
    val worst = maxOf(northSouth, eastWest)
    return if (worst >= 1_000) "%.1f km across".format(worst / 1000.0)
    else "%.0f m across".format(worst)
}
