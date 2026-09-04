package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.rhecyee.firelinemap.car.CheckLine
import com.rhecyee.firelinemap.car.CheckState
import com.rhecyee.firelinemap.car.asReport
import com.rhecyee.firelinemap.car.gatherCarSetupFacts
import com.rhecyee.firelinemap.car.interpret

/**
 * Why the app is, or is not, in the car.
 *
 * Exists because the failure it diagnoses is completely silent: an app the
 * Android Auto host rejects is not listed and nothing anywhere says why. The
 * checks a developer would run over a cable are run here instead, on the phone
 * that is actually in the truck, and the result copies out as text so it can be
 * sent to somebody.
 */
@Composable
fun CarCheckDialog(onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val context = LocalContext.current

    // Live, because whether the phone can see a car right now is half the
    // question and it changes while the dialog is open.
    var connection by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        val type = runCatching { CarConnection(context).type }.getOrNull()
        val observer = Observer<Int> { connection = it }
        type?.observeForever(observer)
        onDispose { type?.removeObserver(observer) }
    }

    val lines = remember(connection) {
        interpret(gatherCarSetupFacts(context, connection))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Android Auto check") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                lines.forEach { line -> CheckRow(line) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCopy(lines.asReport("Fireline Map — Android Auto check"))
            }) { Text("Copy report") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun CheckRow(line: CheckLine) {
    val colour = when (line.state) {
        CheckState.PASS -> Color(0xFF2E7D32)
        CheckState.FAIL -> Color(0xFFB3261E)
        CheckState.WARN -> Color(0xFF8A6D00)
        CheckState.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val mark = when (line.state) {
        CheckState.PASS -> "✓"
        CheckState.FAIL -> "✗"
        CheckState.WARN -> "!"
        CheckState.INFO -> "·"
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            mark,
            color = colour,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                line.label,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                line.detail,
                style = MaterialTheme.typography.labelSmall,
                color = colour
            )
        }
    }
}
