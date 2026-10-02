package com.fitair.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > General > Heart rate: measured max heart rate and birth year (used to scale cardio load). Changing either recomputes load. */
@Composable
fun HeartRateSection() {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var max by remember { mutableStateOf(com.fitair.app.data.dao.PrefDao.get(ctx, LoadDao.PREF_HR_MAX).orEmpty()) }
    var year by remember { mutableStateOf(com.fitair.app.data.dao.PrefDao.get(ctx, LoadDao.PREF_BIRTH_YEAR).orEmpty()) }
    var info by remember { mutableStateOf("") }
    fun refresh() { scope.launch { info = withContext(Dispatchers.IO) { LoadDao.hrMax(ctx).let { "Using ${Math.round(it.value)} bpm (${it.source.label})" } } } }
    LaunchedEffect(Unit) { refresh() }

    SectionHeader("Heart rate")
    Spacer(Modifier.height(Spacing.s))
    OutlinedTextField(max, { v -> max = v.filter { it.isDigit() }.take(3) }, label = { Text("Max heart rate (blank = estimate)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    OutlinedTextField(year, { v -> year = v.filter { it.isDigit() }.take(4) }, label = { Text("Birth year (optional)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    Row {
        TextButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    LoadDao.setHrMax(ctx, max.toDoubleOrNull()?.takeIf { it in 120.0..230.0 })
                    LoadDao.setBirthYear(ctx, year.toIntOrNull()?.takeIf { it in 1900..2100 })
                    com.fitair.app.data.Rebuild.start(ctx)
                }
                refresh()
            }
        }) { Text("Save and recompute load", style = Type.label) }
    }
    Text(info, style = Type.bodySmall, color = dim)
    Text("Cardio load scales effort by your max heart rate. A measured value is best; otherwise it is estimated from your birth year or your data.",
        style = Type.bodySmall, color = dim)
}
