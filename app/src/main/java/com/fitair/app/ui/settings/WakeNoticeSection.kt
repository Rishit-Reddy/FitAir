package com.fitair.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.fitair.app.data.dao.PrefDao
import com.fitair.app.notify.WakeNotice
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** Settings > General > Sleep summary notification: one "You slept ..." message per night, after your sleep has synced. */
@Composable
fun WakeNoticeSection() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(WakeNotice.enabled(ctx)) }
    fun set(v: Boolean) { on = v; PrefDao.setBool(ctx, WakeNotice.PREF_ON, v) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> set(granted) }
    SectionHeader("Sleep summary")
    Spacer(Modifier.height(Spacing.s))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Tell me how I slept", style = Type.body, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { v ->
            if (v && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else set(v)
        })
    }
    Text("One notification per night, once your sleep has reached FitAir. It can come a while after you wake, because your Air syncs through Google Health first.",
        style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
