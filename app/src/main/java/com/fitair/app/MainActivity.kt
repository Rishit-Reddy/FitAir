package com.fitair.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fitair.app.secure.SecretStore
import com.fitair.app.ui.coach.CoachVm
import com.fitair.app.ui.settings.SettingsScreen
import com.fitair.app.ui.theme.FitAirTheme
import com.fitair.app.ui.theme.ThemeMode
import com.fitair.app.ui.today.TodayDest
import com.fitair.app.ui.today.TodayScreen
import com.fitair.app.ui.trends.TrendMetric
import com.fitair.app.ui.trends.TrendScreen
import com.fitair.app.ui.sleep.SleepScreen
import com.fitair.app.ui.agenda.AgendaScreen
import com.fitair.app.ui.components.DetailHeader
import com.fitair.app.ui.load.LoadScreen
import com.fitair.app.ui.theme.NavIcons
import androidx.activity.compose.BackHandler
import com.fitair.app.ui.today.TodayVm
import java.io.IOException

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("fitair", Context.MODE_PRIVATE)
    private val repo = HealthRepo(app)

    var themeMode by mutableStateOf(ThemeMode.fromKey(prefs.getString("theme", null))); private set
    var sdkStatus by mutableStateOf(HealthConnectClient.SDK_UNAVAILABLE); private set
    var permsGranted by mutableStateOf<Boolean?>(null); private set

    var probeRows by mutableStateOf<List<ProbeRow>>(emptyList()); private set
    var probing by mutableStateOf(false); private set
    var probeError by mutableStateOf<String?>(null); private set

    var sessionStart by mutableStateOf<Long?>(null); private set
    var sessionEnd by mutableStateOf<Long?>(null); private set
    var saving by mutableStateOf(false); private set
    var logMessage by mutableStateOf<String?>(null); private set

    var syncing by mutableStateOf(false); private set
    var syncLastMs by mutableStateOf(prefs.getLong(SyncPrefs.LAST, 0L)); private set
    var syncCounts by mutableStateOf(prefs.getString(SyncPrefs.COUNTS, null)); private set
    var syncStatus by mutableStateOf(prefs.getString(SyncPrefs.STATUS, null)); private set
    var dbSizeBytes by mutableStateOf(0L); private set

    // ---- coach chat (legacy path; the Coach tab now uses ui.coach.CoachVm, remove with CoachScreen(MainViewModel)) ----
    private val coachRepo = CoachRepo(app)
    var chat by mutableStateOf(ChatStore.load(app)); private set
    var thinking by mutableStateOf(false); private set
    var chatError by mutableStateOf<String?>(null); private set

    private fun updateChat(m: List<ChatMsg>) { chat = m; ChatStore.save(getApplication(), m) }

    fun sendChat(text: String) {
        if (thinking) return
        AppLog.d("coach: send (${text.length} chars)")
        updateChat(chat + ChatMsg("user", text))
        runAsk()
    }

    fun retryChat() {
        if (thinking || chat.lastOrNull()?.role != "user") {
            // drop trailing notes so the last item is the user message
            val trimmed = chat.dropLastWhile { it.role == "note" }
            if (trimmed.lastOrNull()?.role != "user") return
            updateChat(trimmed)
        }
        AppLog.d("coach: retry")
        runAsk()
    }

    private fun runAsk() {
        viewModelScope.launch {
            thinking = true; chatError = null
            try {
                val history = chat.filter { it.role != "note" }
                val reply = coachRepo.ask(history) { note ->
                    viewModelScope.launch(Dispatchers.Main) { updateChat(chat + ChatMsg("note", note)) }
                }
                updateChat(chat + ChatMsg("assistant", reply))
            } catch (e: IOException) {
                AppLog.d("coach error: ${e.message}")
                chatError = e.message ?: "Request failed"
            } catch (e: Exception) {
                AppLog.d("coach error: $e")
                chatError = e.message ?: e.toString()
            }
            thinking = false
        }
    }

    fun clearChat() { AppLog.d("coach: chat cleared"); ChatStore.clear(getApplication()); chat = emptyList(); chatError = null }

    init {
        // Drive backup is owned by BackupSection; only make sure the periodic job exists once connected.
        if (prefs.getBoolean(DrivePrefs.CONNECTED, false)) DriveScheduler.schedulePeriodic(app)
        SyncScheduler.schedulePeriodic(app)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { DailyMetrics.migrateIfNeeded(app) }.onFailure { AppLog.e("readiness v2 migration failed", it) }
        }
        viewModelScope.launch {
            SyncScheduler.nowFlow(app).collect { infos ->
                syncing = infos.any { !it.state.isFinished }
                syncLastMs = prefs.getLong(SyncPrefs.LAST, 0L)
                syncCounts = prefs.getString(SyncPrefs.COUNTS, null)
                dbSizeBytes = withContext(Dispatchers.IO) { runCatching { LocalStore.get(app).sizeBytes() }.getOrDefault(0L) }
                syncStatus = prefs.getString(SyncPrefs.STATUS, null)
            }
        }
    }

    fun syncNow() { AppLog.d("Sync now tapped"); SyncScheduler.syncNow(getApplication()) }

    fun setTheme(m: ThemeMode) { themeMode = m; prefs.edit().putString("theme", m.key).apply() }
    fun getPref(k: String): String = prefs.getString(k, "") ?: ""
    fun setPref(k: String, v: String) { prefs.edit().putString(k, v).apply() }

    fun checkStatus() {
        sdkStatus = repo.sdkStatus()
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) return
        viewModelScope.launch {
            try {
                permsGranted = repo.hasAllPermissions()
            } catch (e: Exception) {
                permsGranted = false
                AppLog.e("permission check failed", e)
            }
        }
    }

    fun onPermissionResult() = checkStatus()

    fun runProbe() {
        viewModelScope.launch {
            probing = true; probeError = null
            try { probeRows = repo.probe(30) } catch (e: Exception) { probeError = e.message ?: e.toString() }
            probing = false
        }
    }

    fun startSession() { sessionStart = System.currentTimeMillis(); sessionEnd = null; logMessage = null }
    fun stopSession() { sessionEnd = System.currentTimeMillis() }
    fun discardSession() { sessionStart = null; sessionEnd = null }

    fun saveSession(notes: String?, kcal: Double?) {
        val s = sessionStart ?: return
        val e = sessionEnd ?: return
        viewModelScope.launch {
            saving = true
            try {
                repo.logSession(s, e, "Pickleball", notes, kcal)
                logMessage = "Saved to Health Connect"
                sessionStart = null; sessionEnd = null
            } catch (ex: Exception) {
                logMessage = "Save failed: ${ex.message ?: ex}"
            }
            saving = false
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        AppLog.d("app opened")
        SecretStore.migrateFromPrefs(applicationContext)
        setContent {
            val vm: MainViewModel = viewModel()
            FitAirTheme(vm.themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val launcher = rememberLauncherForActivityResult(
                        PermissionController.createRequestPermissionResultContract()
                    ) { vm.onPermissionResult() }
                    LaunchedEffect(Unit) { vm.checkStatus() }

                    when {
                        vm.sdkStatus != HealthConnectClient.SDK_AVAILABLE ->
                            HealthConnectMissingScreen(vm.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                        vm.permsGranted == null -> LoadingScreen()
                        vm.permsGranted == false -> GrantAccessScreen { launcher.launch(HealthPerms.all) }
                        else -> MainContent(vm)
                    }
                }
            }
        }
    }
}

private class TabSpec(val label: String, val off: androidx.compose.ui.graphics.vector.ImageVector, val on: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun MainContent(vm: MainViewModel) {
    // Today · Calendar · Coach · Log. Settings is behind the gear on Today.
    val tabs = listOf(
        TabSpec("Today", NavIcons.TodayOutline, NavIcons.TodayFilled),
        TabSpec("Calendar", NavIcons.CalendarOutline, NavIcons.CalendarFilled),
        TabSpec("Coach", NavIcons.CoachOutline, NavIcons.CoachFilled),
        TabSpec("Log", NavIcons.LogOutline, NavIcons.LogFilled),
    )
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val todayVm: TodayVm = viewModel()
    val coachVm: CoachVm = viewModel()
    // simple state-based stack: a full-screen detail opened from Today; system Back returns to Today
    var dest by rememberSaveable { mutableStateOf<TodayDest?>(null) }
    // hoisted so Today keeps its scroll position while a detail screen is open
    val todayScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    BackHandler(enabled = dest != null) { dest = null }
    if (dest != null) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            when (dest) {
                // "Agenda" now lives in the Calendar tab
                TodayDest.Agenda -> {}
                TodayDest.Sleep -> SleepScreen(onBack = { dest = null })
                TodayDest.Readiness -> TrendScreen(TrendMetric.Readiness) { dest = null }
                TodayDest.Hrv -> TrendScreen(TrendMetric.Hrv) { dest = null }
                TodayDest.RestingHr -> TrendScreen(TrendMetric.RestingHr) { dest = null }
                TodayDest.Load -> LoadScreen(onBack = { dest = null })
                TodayDest.Settings -> Column(Modifier.fillMaxSize()) {
                    DetailHeader("Settings", onBack = { dest = null })
                    Box(Modifier.weight(1f)) { SettingsScreen(vm) }
                }
                null -> {}
            }
        }
        return
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(if (tab == i) t.on else t.off, contentDescription = null) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelLarge) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(todayVm, onOpen = { if (it == TodayDest.Agenda) tab = 1 else dest = it }, scroll = todayScroll, onOpenCalendar = { tab = 1 })
                1 -> AgendaScreen(onBack = null, onOpenSettings = { dest = TodayDest.Settings })
                2 -> CoachScreen(coachVm)
                else -> LogScreen(vm)
            }
        }
    }
}
