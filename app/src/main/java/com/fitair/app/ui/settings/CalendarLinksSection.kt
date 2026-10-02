package com.fitair.app.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fitair.app.AppLog
import com.fitair.app.integrations.calendar.ics.AddResult
import com.fitair.app.integrations.calendar.ics.FeedInfo
import com.fitair.app.integrations.calendar.ics.IcsFeeds
import com.fitair.app.ui.agenda.FeedSignal
import com.fitair.app.ui.components.StatusDot
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Six calm colours for a link's dot: blue, teal, green, amber, plum, slate. */
val LINK_COLORS: List<Pair<String, Int>> = listOf(
    "Blue" to 0xFF4A78B5.toInt(), "Teal" to 0xFF3E8E88.toInt(), "Green" to 0xFF6B8E3A.toInt(),
    "Amber" to 0xFFB7791F.toInt(), "Plum" to 0xFF8A5A8C.toInt(), "Slate" to 0xFF6B7280.toInt(),
)

/** Settings > Calendar > Calendar links: the subscribed iCal links, add / edit / refresh / remove. The address itself is never shown. */
@Composable
fun CalendarLinksSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var feeds by remember { mutableStateOf<List<FeedInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FeedInfo?>(null) }
    var removing by remember { mutableStateOf<FeedInfo?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    suspend fun reload() {
        feeds = try { IcsFeeds.list(ctx) } catch (e: CancellationException) { throw e } catch (e: Exception) { AppLog.d("calendar links: list failed: ${e.message}"); feeds }
        nowMs = System.currentTimeMillis(); loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    Spacer(Modifier.height(Spacing.l))
    Text("Calendar links", style = Type.title)
    Text("Paste the private iCal address of a calendar (Google Calendar: Settings, your calendar, Secret address in iCal format). " +
        "FitAir reads it itself, so your shifts show up here.", style = Type.bodySmall, color = dim)
    Spacer(Modifier.height(Spacing.s))
    if (loaded && feeds.isEmpty()) Text("No links yet.", style = Type.bodySmall, color = dim)
    feeds.forEach { f ->
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Color(f.color))
                Spacer(Modifier.width(Spacing.s))
                Text(f.label.ifBlank { "Calendar link" }, style = Type.body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (f.work) Text("shifts", style = Type.caption, color = dim)
            }
            Text(if (busyId == f.id) "Updating…" else Copy.feedStatus(f.eventCount, f.lastOkMs, f.lastError, nowMs),
                style = Type.bodySmall, color = dim, modifier = Modifier.padding(start = Spacing.l))
            Row {
                TextButton(enabled = busyId == null, onClick = {
                    scope.launch {
                        busyId = f.id; note = null
                        try {
                            val r = IcsFeeds.refresh(ctx, f.id).firstOrNull()
                            note = when {
                                r == null -> null
                                !r.ok -> Copy.hideUrls(r.message ?: "Could not update this link.")
                                r.changed -> "Updated."
                                else -> "Already up to date."
                            }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { note = "Could not update this link." }
                        busyId = null; reload(); FeedSignal.bump()
                    }
                }) { Text("Refresh", style = Type.label) }
                TextButton(enabled = busyId == null, onClick = { editing = f }) { Text("Edit", style = Type.label) }
                TextButton(enabled = busyId == null, onClick = { removing = f }) { Text("Remove", style = Type.label) }
            }
        }
    }
    note?.let { Text(it, style = Type.bodySmall, color = dim) }
    TextButton(onClick = { adding = true }) { Text("Add calendar link", style = Type.label) }

    if (adding) LinkDialog(null, onDismiss = { adding = false }, onDone = { adding = false; scope.launch { reload(); FeedSignal.bump() } })
    editing?.let { f -> LinkDialog(f, onDismiss = { editing = null }, onDone = { editing = null; scope.launch { reload(); FeedSignal.bump() } }) }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this link?") },
            text = { Text("“${f.label.ifBlank { "Calendar link" }}” and its events will disappear from FitAir. The calendar itself is not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        try { IcsFeeds.remove(ctx, f.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { note = "Could not remove the link." }
                        reload(); FeedSignal.bump()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Add ([initial] null: needs a link that passes "Check link") or edit (name, colour and the two switches; the link is never shown). */
@Composable
private fun LinkDialog(initial: FeedInfo?, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val z = remember { ZoneId.systemDefault() }
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf(initial?.label ?: "Work shifts") }
    var color by remember { mutableIntStateOf(initial?.color ?: LINK_COLORS[1].second) }
    var work by remember { mutableStateOf(initial?.work ?: true) }
    var share by remember { mutableStateOf(initial?.shareTitles ?: true) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var previewOk by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var failText by remember { mutableStateOf<String?>(null) }
    val problem = if (initial == null) Copy.linkInputProblem(url) else null
    val canCheck = initial == null && url.isNotEmpty() && problem == null && !checking && !saving
    val canSave = !saving && !checking && label.isNotBlank() && (initial != null || previewOk)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial == null) "Add calendar link" else "Edit calendar link") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (initial == null) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = Copy.cleanLinkInput(it); previewOk = false; previewText = null; failText = null },
                        label = { Text("Calendar link") }, placeholder = { Text("https:// or webcal://") },
                        singleLine = true, isError = problem != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                        supportingText = { if (problem != null) Text(problem) },
                    )
                    TextButton(onClick = {
                        val pasted = try {
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
                                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        } catch (e: Exception) { null }
                        if (pasted.isNullOrBlank()) failText = "Nothing to paste. Copy the calendar link first."
                        else { url = Copy.cleanLinkInput(pasted); previewOk = false; previewText = null; failText = null }
                    }) { Text("Paste from clipboard", style = Type.label) }
                }
                OutlinedTextField(label, { label = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Colour", style = Type.bodySmall, color = dim)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    LINK_COLORS.forEach { (name, c) ->
                        val sel = c == color
                        Box(
                            Modifier.size(Spacing.minTouch - 8.dp).clip(CircleShape).clickable(role = Role.RadioButton) { color = c }
                                .semantics { contentDescription = name + if (sel) ", selected" else "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                    .then(if (sel) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), CircleShape) else Modifier),
                            )
                        }
                    }
                }
                SwitchLine("These are work shifts", "Lets the coach see how hard your shifts are.", work) { work = it }
                SwitchLine("Share event titles with the coach", "Off: the coach only sees when you are busy. On: titles are sent to your AI provider.", share) { share = it }
                if (initial == null) {
                    OutlinedButton(enabled = canCheck, onClick = {
                        scope.launch {
                            checking = true; previewOk = false; previewText = null; failText = null
                            try {
                                val r = IcsFeeds.preview(url)
                                val next = r.next.firstOrNull()
                                previewOk = r.error.isNullOrBlank() && r.events > 0
                                previewText = Copy.previewLine(r.events, next?.title, next?.let { Copy.dayClock(it.begin, z) }, r.error)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { previewText = "Could not check the link. Try again." }
                            checking = false
                        }
                    }) { Text(if (checking) "Checking…" else "Check link", style = Type.label) }
                    (previewText ?: failText)?.let { Text(it, style = Type.bodySmall, color = if (previewOk) MaterialTheme.colorScheme.onSurface else dim) }
                    if (previewText == null && failText == null) Text("Check the link before saving.", style = Type.bodySmall, color = dim)
                } else failText?.let { Text(it, style = Type.bodySmall, color = dim) }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                scope.launch {
                    saving = true; failText = null
                    try {
                        if (initial == null) {
                            when (val r = IcsFeeds.add(ctx, url, label.trim(), color, work, share)) {
                                is AddResult.Ok -> { url = ""; onDone(); return@launch }
                                is AddResult.Fail -> failText = Copy.hideUrls(r.message)
                            }
                        } else { IcsFeeds.update(ctx, initial.id, label.trim(), color, work, share); onDone(); return@launch }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failText = "Could not save. Try again." }
                    saving = false
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchLine(title: String, why: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.body, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.s))
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Text(why, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
