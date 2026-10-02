package com.fitair.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
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
import java.io.IOException

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("fitair", Context.MODE_PRIVATE)
    private val repo = HealthRepo(app)

    var themeMode by mutableStateOf(ThemeMode.fromKey(prefs.getString("theme", null))); private set
    var sdkStatus by mutableStateOf(HealthConnectClient.SDK_UNAVAILABLE); private set
    var permsGranted by mutableStateOf<Boolean?>(null); private set

    var stats by mutableStateOf<TodayStats?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

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

    // ---- google drive ----
    var driveConnected by mutableStateOf(prefs.getBoolean(DrivePrefs.CONNECTED, false)); private set
    var driveLastMs by mutableStateOf(0L); private set
    var driveSizeBytes by mutableStateOf(0L); private set
    var driveFailedBefore by mutableStateOf(false); private set
    var driveState by mutableStateOf(BackupState()); private set

    /** Backup progress as shown in Settings: live state, or a remembered failure after a process restart. */
    val driveShown: BackupState get() =
        if (driveState.phase == BackupPhase.Idle && driveFailedBefore) BackupState(BackupPhase.Failed, 0) else driveState

    private fun loadDrive() {
        driveConnected = prefs.getBoolean(DrivePrefs.CONNECTED, false)
        driveLastMs = prefs.getLong(DrivePrefs.LAST, 0L)
        driveSizeBytes = prefs.getLong(DrivePrefs.SIZE, 0L)
        driveFailedBefore = prefs.getBoolean(DrivePrefs.FAILED, false)
    }
    private val driveListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k?.startsWith("drive_") == true) loadDrive()
    }

    /** Starts authorization; [launch] is called if Google needs to show a consent screen. */
    fun connectDrive(launch: (androidx.activity.result.IntentSenderRequest) -> Unit) {
        viewModelScope.launch {
            AppLog.d("drive: connect tapped")
            when (val a = DriveAuth.authorize(getApplication())) {
                is DriveAuth.NeedsResolution -> {
                    AppLog.d("drive: launching consent screen")
                    launch(androidx.activity.result.IntentSenderRequest.Builder(a.intent).build())
                }
                is DriveAuth.Token -> onDriveConnected()
                is DriveAuth.Failed -> {
                    AppLog.e("drive: connect failed", a.error)
                }
            }
        }
    }

    fun onDriveResult(data: android.content.Intent?) {
        try {
            val r = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(getApplication())
                .getAuthorizationResultFromIntent(data)
            if (r.accessToken != null) onDriveConnected() else AppLog.d("drive: consent returned no token")
        } catch (e: Exception) {
            AppLog.e("drive: consent cancelled/failed", e)
        }
    }

    private fun onDriveConnected() {
        AppLog.d("drive: connected")
        prefs.edit().putBoolean(DrivePrefs.CONNECTED, true).putBoolean(DrivePrefs.FAILED, false).apply()
        DriveScheduler.schedulePeriodic(getApplication())
        backUpNow()
    }

    fun backUpNow() {
        AppLog.d("drive: Back up now tapped")
        DriveBackup.markQueued()
        DriveScheduler.backupNow(getApplication())
    }

    // ---- coach chat ----
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

    // ---- readiness ----
    var readinessScore by mutableStateOf<String?>(null); private set
    var readinessNote by mutableStateOf<String?>(null); private set
    var readinessOffline by mutableStateOf(false); private set

    fun loadReadiness() {
        viewModelScope.launch {
            try {
                val j = withContext(Dispatchers.IO) {
                    ServerApi.get(getApplication(), "/readiness?date=${java.time.LocalDate.now()}")
                }
                val sc = j.opt("score")
                readinessScore = if (sc is Number) Math.round(sc.toDouble()).toString() else null
                val notes = j.optJSONArray("notes")
                readinessNote = notes?.optString(0)?.takeIf { it.isNotBlank() }
                readinessOffline = false
            } catch (e: Exception) {
                AppLog.d("readiness failed: ${e.message}")
                readinessScore = null; readinessNote = null; readinessOffline = true
            }
        }
    }

    init {
        loadDrive()
        prefs.registerOnSharedPreferenceChangeListener(driveListener)
        if (driveConnected) DriveScheduler.schedulePeriodic(app)
        viewModelScope.launch { DriveBackup.state.collect { driveState = it; loadDrive() } }
        SyncScheduler.schedulePeriodic(app)
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
                val ok = repo.hasAllPermissions()
                permsGranted = ok
                if (ok && stats == null) refresh()
            } catch (e: Exception) {
                permsGranted = false
                error = e.message
            }
        }
    }

    fun onPermissionResult() = checkStatus()

    fun refresh() {
        viewModelScope.launch {
            loading = true; error = null
            try { stats = repo.today() } catch (e: Exception) { error = e.message ?: e.toString() }
            loading = false
        }
    }

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
        setContent {
            val vm: MainViewModel = viewModel()
            FitAirTheme(vm.themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val launcher = rememberLauncherForActivityResult(
                        PermissionController.createRequestPermissionResultContract()
                    ) { vm.onPermissionResult() }
                    val driveLauncher = rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
                    ) { vm.onDriveResult(it.data) }
                    LaunchedEffect(Unit) { vm.checkStatus() }

                    when {
                        vm.sdkStatus != HealthConnectClient.SDK_AVAILABLE ->
                            HealthConnectMissingScreen(vm.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                        vm.permsGranted == null -> LoadingScreen()
                        vm.permsGranted == false -> GrantAccessScreen { launcher.launch(HealthPerms.all) }
                        else -> MainContent(vm) { vm.connectDrive { driveLauncher.launch(it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainContent(vm: MainViewModel, onConnectDrive: () -> Unit) {
    val tabs = listOf("Today", "Coach", "Log", "Data", "Settings")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = {}, label = { Text(t, style = MaterialTheme.typography.labelLarge) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
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
                0 -> TodayScreen(vm)
                1 -> CoachScreen(vm)
                2 -> LogScreen(vm)
                3 -> DataScreen(vm)
                else -> SettingsScreen(vm, onConnectDrive)
            }
        }
    }
}
