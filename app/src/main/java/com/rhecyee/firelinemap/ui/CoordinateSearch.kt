package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter
import com.rhecyee.firelinemap.util.CoordinateParseResult
import com.rhecyee.firelinemap.util.CoordinateParser
import com.rhecyee.firelinemap.util.ParsedCoordinate

/**
 * Type a coordinate as bare numbers.
 *
 * Built for copying a position off the radio. Whoever is reading it out will
 * not say which format it is in, so the count of numbers decides: two is
 * decimal degrees, four is degrees and minutes, six adds seconds. The running
 * interpretation is shown the whole way, because two numbers is a valid
 * position and also the halfway point of a longer one -- watching it resolve
 * is what makes that safe.
 */
@Composable
fun CoordinateSearchDialog(
    onDismiss: () -> Unit,
    onGo: (ParsedCoordinate) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val result = remember(input) { CoordinateParser.parse(input) }
    val coordinate = (result as? CoordinateParseResult.Success)?.coordinate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to coordinate") },
        text = {
            Column {
                Text(
                    "Type the numbers, spaces between. Two for decimal degrees, " +
                        "four for degrees and minutes, six with seconds.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 26.sp,
                        textAlign = TextAlign.Center
                    ),
                    // Numeric with symbols, so the pad carries the minus and
                    // the decimal point without a trip to the letter keyboard.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    placeholder = { Text("45 43.177 117 16.040") }
                )

                val message: String
                val colour: Color
                when {
                    coordinate != null -> {
                        message = CoordinateFormatter.format(
                            coordinate.latitude, coordinate.longitude, CoordinateFormat.DDM
                        )
                        colour = Color(0xFF2E7D32)
                    }
                    result is CoordinateParseResult.Invalid -> {
                        message = result.message
                        colour = Color(0xFFB3261E)
                    }
                    else -> {
                        message = (result as CoordinateParseResult.Incomplete).message
                        colour = Color(0xFF5F6368)
                    }
                }
                Text(
                    message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    color = colour,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium
                )
                if (coordinate != null) {
                    Text(
                        "Read as ${coordinate.format.label}" +
                            if (coordinate.assumedHemisphere) " · assumed N and W" else "",
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = Color(0xFF5F6368),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { coordinate?.let(onGo) },
                enabled = coordinate != null
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
