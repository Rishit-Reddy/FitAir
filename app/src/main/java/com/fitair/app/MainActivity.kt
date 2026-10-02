package com.fitair.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.launch

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
    var testResult by mutableStateOf<String?>(null); private set
    var testing by mutableStateOf(false); private set

    init {
        SyncScheduler.schedulePeriodic(app)
        viewModelScope.launch {
            SyncScheduler.nowFlow(app).collect { infos ->
                syncing = infos.any { !it.state.isFinished }
                syncLastMs = prefs.getLong(SyncPrefs.LAST, 0L)
                syncCounts = prefs.getString(SyncPrefs.COUNTS, null)
                syncStatus = prefs.getString(SyncPrefs.STATUS, null)
            }
        }
    }

    fun syncNow() { SyncScheduler.syncNow(getApplication()) }

    fun testConnection() {
        viewModelScope.launch {
            testing = true; testResult = null
            testResult = SyncRepo.testConnection(getPref(SyncPrefs.ADDR), getPref(SyncPrefs.KEY))
            testing = false
        }
    }

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

@Composable
private fun MainContent(vm: MainViewModel) {
    val tabs = listOf("Today", "Log", "Data", "Settings")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
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
                1 -> LogScreen(vm)
                2 -> DataScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
