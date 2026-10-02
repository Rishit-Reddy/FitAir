package com.fitair.app.ui.weight

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.fitair.app.data.WeightSync
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/** Log tab: enter a weight in kg. It is kept in FitAir and, when Health Connect weight access is granted, written to Health Connect too. */
@Composable
fun WeightEntry() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var text by remember { mutableStateOf("") }
    var linked by remember { mutableStateOf<Boolean?>(null) }
    var last by remember { mutableStateOf<Pair<Long, Double>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    fun refresh() { scope.launch { withContext(Dispatchers.IO) { last = WeightSync.latest(ctx); linked = WeightSync.granted(ctx) } } }
    LaunchedEffect(Unit) { refresh() }
    val hc = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        linked = granted.containsAll(WeightSync.perms)
        if (linked == true) scope.launch { withContext(Dispatchers.IO) { WeightSync.sync(ctx) }; refresh() }
    }
    SectionHeader("Weight")
    Spacer(Modifier.height(Spacing.s))
    last?.let { (t, kg) ->
        Text("Last: ${"%.1f".format(Locale.US, kg)} kg · ${Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DAY)}", style = Type.bodySmall, color = dim)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.replace(',', '.'); note = null },
            label = { Text("kg") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        TextButton(enabled = text.toDoubleOrNull() != null, onClick = {
            val kg = text.toDoubleOrNull() ?: return@TextButton
            scope.launch {
                val ok = withContext(Dispatchers.IO) { WeightSync.save(ctx, kg).also { if (it) WeightSync.sync(ctx) } }
                note = if (ok) "Saved" else "That does not look like a body weight."
                if (ok) text = ""
                refresh()
            }
        }) { Text("Save", style = Type.label) }
    }
    note?.let { Text(it, style = Type.bodySmall, color = dim) }
    when (linked) {
        true -> Text("Linked with Health Connect: weights from other apps come in, and yours go out.", style = Type.bodySmall, color = dim)
        false -> {
            Text("Not linked with Health Connect. Link it to read weights from Google Health and send yours there.", style = Type.bodySmall, color = dim)
            TextButton(onClick = { hc.launch(WeightSync.perms) }) { Text("Link", style = Type.label) }
        }
        null -> Unit
    }
}
