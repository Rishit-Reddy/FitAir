package com.fitair.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** Display name for an explain key, the same everywhere. */
fun explainTitle(key: String): String = when (key) {
    "readiness" -> "Readiness"
    "hrv", "recovery_signal" -> "Recovery signal (HRV)"
    "resting_hr" -> "Resting heart rate"
    "sleep" -> "Sleep score"
    "sleep_need" -> "Sleep need and debt"
    "load" -> "Cardio load"
    "subjective" -> "How you feel"
    else -> key
}

/** "What is this?": at most two sentences from [Copy.explain], and an optional "Good to know" paragraph with the honest limits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainSheet(key: String, onDismiss: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Shapes.sheet,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xxl)) {
            SectionHeader("What is this?")
            Spacer(Modifier.height(Spacing.xs))
            Text(explainTitle(key), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.m))
            Text(Copy.explain(key) ?: "No explanation for this yet.", style = Type.body)
            Copy.explainMore(key)?.let {
                Spacer(Modifier.height(Spacing.l))
                SectionHeader("Good to know")
                Spacer(Modifier.height(Spacing.xs))
                Text(it, style = Type.bodySmall, color = dim)
            }
        }
    }
}
