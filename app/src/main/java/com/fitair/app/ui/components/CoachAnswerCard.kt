package com.fitair.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitair.app.coach.AnswerParser
import com.fitair.app.coach.CoachAnswer

/** Structured coach answer (doc 2.6): headline, bullets, number chips, "More" detail, follow-up chips, ToolTrace line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachAnswerCard(
    answer: CoachAnswer,
    trace: String,
    truncated: Boolean,
    canExplain: Boolean,
    onExplain: () -> Unit,
    onFollowUp: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // a long ("Explain more") answer opens with its detail visible
    var showDetail by remember(answer) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (answer.headline.isNotEmpty()) Text(answer.headline, style = MaterialTheme.typography.titleMedium)
        answer.bullets.forEach { b ->
            Row {
                Text("•", style = MaterialTheme.typography.bodyLarge, color = dim)
                Spacer(Modifier.width(8.dp))
                Text(b, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (answer.numbers.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                answer.numbers.forEach { n ->
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(AnswerParser.chipText(n), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
        }
        if (answer.detail.isNotEmpty()) {
            if (showDetail) Text(answer.detail, style = MaterialTheme.typography.bodyMedium, color = dim)
            TextButton(onClick = { showDetail = !showDetail }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showDetail) "Less" else "More")
            }
        }
        if (truncated) Text("Answer was cut off.", style = MaterialTheme.typography.bodySmall, color = dim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (truncated) SuggestionChip(onClick = onContinue, label = { Text("Continue") })
            if (canExplain && answer.detail.isEmpty()) SuggestionChip(onClick = onExplain, label = { Text("Explain more") })
            answer.followUps.forEach { f -> SuggestionChip(onClick = { onFollowUp(f) }, label = { Text(f) }) }
        }
        if (trace.isNotEmpty()) Text(trace, style = MaterialTheme.typography.bodySmall, color = dim)
    }
}
