package com.fitair.app

import com.fitair.app.coach.AnswerParser
import com.fitair.app.ui.coach.CoachVm
import com.fitair.app.ui.components.CoachAnswerCard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private val SUGGESTIONS = listOf("How ready am I today?", "Why is my readiness where it is?", "Should I train hard today?")

/** Minimal markdown: **bold** inline and "- " bullets. */
private fun renderMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    src.lines().forEachIndexed { li, raw ->
        if (li > 0) append("\n")
        val t = raw.trimStart()
        val line = if (t.startsWith("- ") || t.startsWith("* ")) "•  " + t.drop(2) else raw
        var i = 0
        while (i < line.length) {
            val s = line.indexOf("**", i)
            val e = if (s >= 0) line.indexOf("**", s + 2) else -1
            if (s < 0 || e < 0) { append(line.substring(i)); break }
            append(line.substring(i, s))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.substring(s + 2, e)) }
            i = e + 2
        }
    }
}

/** Coach tab backed by [CoachVm] (the target path). */
@Composable
fun CoachScreen(vm: CoachVm) = CoachContent(
    msgs = vm.chat, thinking = vm.thinking, status = vm.status, error = vm.chatError,
    onSend = vm::sendChat, onRetry = vm::retryChat, onClear = vm::clearChat,
    onExplain = vm::explainMore, onContinue = vm::continueChat,
)

/** Legacy path backed by MainViewModel; delete together with MainViewModel's chat code once CoachVm is wired. */
@Composable
fun CoachScreen(vm: MainViewModel) = CoachContent(
    msgs = vm.chat, thinking = vm.thinking, status = null, error = vm.chatError,
    onSend = vm::sendChat, onRetry = vm::retryChat, onClear = vm::clearChat, onExplain = null, onContinue = {},
)

@Composable
private fun CoachContent(
    msgs: List<ChatMsg>, thinking: Boolean, status: String?, error: String?,
    onSend: (String) -> Unit, onRetry: () -> Unit, onClear: () -> Unit, onExplain: (() -> Unit)?, onContinue: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    val extra = (if (thinking) 1 else 0) + (if (error != null) 1 else 0)
    LaunchedEffect(msgs.size, thinking, error) {
        val n = msgs.size + extra
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    fun send(t: String) {
        val s = t.trim()
        if (s.isEmpty() || thinking) return
        input = ""
        onSend(s)
    }
    val lastAssistant = msgs.indexOfLast { it.role == "assistant" }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption("Coach")
            Spacer(Modifier.weight(1f))
            if (msgs.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear chat") }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (msgs.isEmpty() && !thinking) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center) {
                Text("Ask about your training, sleep or recovery.", style = MaterialTheme.typography.bodyLarge, color = dim)
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { s ->
                        SuggestionChip(onClick = { send(s) }, label = { Text(s) })
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(msgs) { idx, m ->
                    when (m.role) {
                        "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                m.text, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.widthIn(max = 300.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        "note" -> Text(m.text, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = dim)
                        else -> {
                            val a = AnswerParser.fromJson(m.json)
                            if (a != null) CoachAnswerCard(
                                answer = a, trace = m.trace, truncated = m.truncated,
                                canExplain = onExplain != null && idx == lastAssistant && !m.long && !thinking,
                                onExplain = { onExplain?.invoke() }, onFollowUp = { send(it) }, onContinue = onContinue,
                            ) else Text(renderMarkdown(m.text), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (thinking) item { Text(status ?: "thinking…", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = dim) }
                error?.let { e ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e, style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.weight(1f, fill = false))
                            TextButton(onClick = { onRetry() }) { Text("Retry") }
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Ask your coach") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { send(input) }, enabled = input.isNotBlank() && !thinking) { Text("Send") }
        }
    }
}
