package dev.lucy.phone.launcher

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.lucy.phone.BuildConfig
import dev.lucy.phone.R
import dev.lucy.phone.LucyApp
import dev.lucy.phone.configuration.AppEntry
import dev.lucy.phone.configuration.AdministratorEntry
import dev.lucy.phone.configuration.DevicePolicy
import dev.lucy.phone.configuration.Onboarding
import dev.lucy.phone.filtering.ProbeResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

data class HomeState(
    val apps: List<AppEntry> = emptyList(), val allowedApps: List<AppEntry> = emptyList(),
    val catalog: List<AppEntry> = emptyList(), val owner: Boolean = false,
    val activated: Boolean = false, val adminConfigured: Boolean? = null, val maintenance: Boolean = false,
    val totpConfigured: Boolean = false,
    val status: String = "Loading", val baseline: String = "", val errors: List<String> = emptyList(),
    val filterDetail: String = "Not configured", val support: Map<String, String> = emptyMap(), val log: List<String> = emptyList(),
    val draftApps: List<AppEntry> = emptyList(), val blockers: List<String> = emptyList(),
    val network: String = "Checking network", val filterPassed: Boolean = false, val filterChecks: List<ProbeResult> = emptyList(),
    val temporaryInternet: Boolean = false, val internetMinutes: Long = 0, val maintenanceMinutes: Long = 0,
    val appsReviewed: Boolean = false, val recoverySaved: Boolean = false, val navigationTested: Boolean = false,
    val readFailed: Boolean = false,
) {
    val administratorEntry get() = AdministratorEntry.resolve(adminConfigured, activated, readFailed)
}

class HomeActivity : ComponentActivity() {
    private val app get() = application as LucyApp
    private var state by mutableStateOf(HomeState())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var screen by mutableStateOf("home")
    private var session: String? = null
    private var recoveryCode by mutableStateOf("")
    private var totpEnrollmentUri by mutableStateOf("")
    private var totpSetupKey by mutableStateOf("")
    private var resumed = false
    private var adminPage by mutableStateOf("overview")
    private val adminSessionBoundary = AdminSessionBoundary()
    private var appearanceMode by mutableStateOf(AppearanceMode.SYSTEM)
    private var ringerMode by mutableIntStateOf(AudioManager.RINGER_MODE_NORMAL)
    private fun refreshRingerMode() {
        ringerMode = getSystemService(AudioManager::class.java).ringerMode
    }
    private fun ringerLabel() = when (ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "Silent"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
        else -> "Sound"
    }
    private fun changeRingerMode(mode: Int) {
        runCatching {
            val audio = getSystemService(AudioManager::class.java)
            check(!audio.isVolumeFixed) { "This device does not allow sound-mode changes" }
            audio.ringerMode = mode
            refreshRingerMode()
            check(ringerMode == mode) { "Android did not change the sound mode" }
            message = ""
        }.onFailure {
            refreshRingerMode()
            message = if (it is SecurityException)
                "Allow Lucy's sound-mode access in Administration → Security & recovery during supervised setup"
            else it.message ?: "Could not change sound mode"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearanceMode = app.appearance.get()
        window.isNavigationBarContrastEnforced = false
        val handoff = app.consumeAdminActivityHandoff()
        if (handoff != null && app.auth.validSession(handoff.session)) {
            session = handoff.session; adminPage = handoff.page; navigate("admin")
        } else app.adminReturnPage?.let { adminPage = it; navigate("login") }
        setContent {
            val dark = appearanceMode.usesDarkTheme(isSystemInDarkTheme())
            val colors = lucyColorScheme(dark)
            val navigationBar = if (dark) Color(0xFF090B09) else Color(0xFF242A25)
            SideEffect {
                val background = colors.background.toArgb()
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(background) else SystemBarStyle.light(background, background),
                    navigationBarStyle = SystemBarStyle.dark(navigationBar.toArgb()))
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
            }
            MaterialTheme(colorScheme = colors) {
                // Paint a high-contrast backing even when the emulator's live nav-mode switch loses tint state.
                Surface(Modifier.fillMaxSize(), color = navigationBar) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                        Surface(Modifier.fillMaxSize()) { Content() }
                    }
                }
            }
        }
        lifecycleScope.launch { app.updates.collect { refresh() } }
        lifecycleScope.launch {
            while (true) {
                delay(1_000)
                if (screen == "admin" && !app.auth.validSession(session)) {
                    lockAdministration(); navigate("login"); message = "Session locked after inactivity"
                }
                if (resumed) ensureLockTask()
            }
        }
        lifecycleScope.launch { while (true) {
            delay(60_000)
            if (resumed) {
                app.executor.execute { runCatching { app.engine.checkFilter() }; refresh() }
            }
        } }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        if (screen == "admin" && !app.auth.validSession(session)) {
            lockAdministration(); navigate("login"); message = "Administrator session expired; authenticate again"
        }
        refreshRingerMode(); ensureLockTask(); refresh()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ensureLockTask()
    }
    override fun onPause() {
        resumed = false
        if (screen == "admin") {
            cancelTotpEnrollment()
            app.adminReturnPage = adminPage
            if (!adminSessionBoundary.shouldPreserveAdminOnPause(pausingAdministration = true)) {
                // Hide management UI on an unrelated app switch, but keep the short-lived token
                // so explicitly re-entering Administration does not demand authentication again.
                screen = "home"
                app.adminReturnPage = null
            }
        } else {
            adminSessionBoundary.shouldPreserveAdminOnPause(pausingAdministration = false)
        }
        super.onPause()
    }
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (screen == "admin") session?.let { if (app.auth.validSession(it)) app.auth.requireSession(it) }
    }
    private fun navigate(target: String) {
        screen = target; message = ""
        if (target != "admin") cancelTotpEnrollment()
        if (target == "home") app.adminReturnPage = null
        if (target in setOf("setup", "login", "recover", "admin", "adminUnavailable", "wifi")) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun lockAdministration() {
        app.clearAdminActivityHandoff()
        app.auth.lock()
        totpEnrollmentUri = ""
        totpSetupKey = ""
        session = null
    }
    private fun cancelTotpEnrollment() {
        totpEnrollmentUri = ""
        totpSetupKey = ""
        app.auth.cancelTotpEnrollment()
    }
    private fun expectAdminToolPause() {
        adminSessionBoundary.expectExternalPause()
        // A failed/no-op activity launch must not authorize some unrelated later app switch.
        window.decorView.postDelayed({ if (resumed) adminSessionBoundary.cancelExternalPause() }, 1_000)
    }
    private fun startExpectedAdminActivity(intent: Intent) {
        if (screen == "admin") expectAdminToolPause()
        try { startActivity(intent) }
        catch (failure: Exception) {
            adminSessionBoundary.cancelExternalPause()
            throw failure
        }
    }
    private fun openAdministration() {
        if (app.auth.validSession(session)) {
            app.auth.requireSession(session ?: "")
            navigate("admin")
            return
        }
        lockAdministration()
        when (state.administratorEntry) {
            AdministratorEntry.LOADING -> Unit
            AdministratorEntry.SETUP -> navigate("setup")
            AdministratorEntry.LOGIN -> navigate("login")
            AdministratorEntry.UNAVAILABLE -> navigate("adminUnavailable")
        }
    }
    private fun refresh() {
        app.executor.execute {
            runCatching {
                val policy = app.engine.currentPolicy()
                val json = app.store.read()
                val filter = json.optJSONObject("filter")
                val activated = app.store.configured()
                val maintenance = app.engine.inMaintenance()
                val errors = app.store.errors() + if (activated && !maintenance) app.engine.verify(policy) else emptyList()
                val fresh = app.engine.filterPassed()
                val status = when {
                    app.engine.temporaryInternetActive() -> "Unfiltered · setup only"
                    maintenance -> "Supervised maintenance"
                    !activated -> if (app.engine.isOwner) "Setup incomplete" else "Launcher preview"
                    errors.isNotEmpty() || !fresh || !app.engine.isOwner -> "Attention required"
                    !app.engine.lockTaskRunning() -> "Starting managed HOME"
                    BuildConfig.ALLOW_DEBUGGING -> "Development · ADB enabled"
                    else -> "Ready"
                }
                val events = json.optJSONArray("log")
                val checklist = app.store.onboarding()
                val draftApps = app.engine.draftPolicy().apps
                val checks = filter?.optJSONArray("checks")
                val labelOrder = compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName }
                val allowedApps = policy.apps.sortedWith(labelOrder)
                HomeState(
                    apps = allowedApps.filter { it.packageName in policy.homePackages }, allowedApps = allowedApps,
                    catalog = app.engine.inventory.apps(policy), owner = app.engine.isOwner, activated = activated,
                    adminConfigured = app.auth.configured(), maintenance = maintenance, totpConfigured = app.auth.totpConfigured(),
                    status = status, baseline = policy.baseline,
                    errors = errors, filterDetail = filter?.let { it.optString("detail") + if (!fresh && it.optBoolean("passed")) " · recheck required on the current network" else "" } ?: "Not checked",
                    support = policy.support, log = events?.let { (maxOf(0, it.length() - 10) until it.length()).map { i -> it.getJSONObject(i).getString("event") }.reversed() } ?: emptyList(),
                    draftApps = draftApps, blockers = app.engine.onboardingBlockers(), network = app.engine.networkDescription(), filterPassed = fresh,
                    filterChecks = checks?.let { (0 until it.length()).map { i -> it.getJSONObject(i).let { c -> ProbeResult(c.getString("label"), c.getBoolean("passed"), c.getString("detail")) } } } ?: emptyList(),
                    temporaryInternet = app.engine.temporaryInternetActive(), internetMinutes = app.engine.remainingMinutes(true), maintenanceMinutes = app.engine.remainingMinutes(),
                    appsReviewed = checklist.optJSONArray("reviewedApps")?.let { (0 until it.length()).map(it::getString).toSet() } == draftApps.map { it.packageName }.toSet(),
                    recoverySaved = checklist.optBoolean("recoverySaved"), navigationTested = checklist.optBoolean("navigationTested"))
            }.onSuccess { value -> runOnUiThread {
                state = value
                // A stale setup page must not survive a completed credentials read. Keep a newly
                // issued recovery code visible until acknowledged, including after async refresh.
                if (!busy && recoveryCode.isBlank() &&
                    ((screen == "setup" && value.administratorEntry != AdministratorEntry.SETUP) ||
                        screen == "adminUnavailable")) openAdministration()
                ensureLockTask()
            } }.onFailure { e -> runOnUiThread {
                state = state.copy(adminConfigured = null, readFailed = true)
                message = e.message ?: "Unable to read configuration"
            } }
        }
    }
    private fun ensureLockTask() {
        if (!resumed || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (app.engine.inMaintenance()) {
            // PolicyEngine exits the entire managed task session. stopLockTask()
            // here only removes this HOME task when another task owns the root;
            // Android recreates HOME and this callback would remove it again.
            return
        }
        // Enforce persisted policy immediately, without waiting for inventory/network UI refresh.
        runCatching {
            if (app.engine.isOwner && app.store.configured() && !app.engine.inMaintenance() &&
                !app.engine.lockTaskRunning() && app.engine.dpm.isLockTaskPermitted(packageName)) startLockTask()
        }.onFailure { message = "Could not start lock task: ${it.message}" }
    }
    private fun work(success: (String) -> Unit = { message = it }, block: () -> String) {
        if (busy) return
        busy = true; message = ""
        app.executor.execute {
            val result = runCatching(block)
            runOnUiThread {
                busy = false
                result.onSuccess(success).onFailure { message = it.message ?: "Operation failed" }
                refresh()
            }
        }
    }
    private fun <T> workValue(success: (T) -> Unit, block: () -> T) {
        if (busy) return
        busy = true; message = ""
        app.executor.execute {
            val result = runCatching(block)
            runOnUiThread {
                busy = false
                result.onSuccess(success).onFailure { message = it.message ?: "Operation failed" }
                refresh()
            }
        }
    }
    private fun openApp(entry: AppEntry) {
        runCatching {
            val intent = app.engine.inventory.launchIntent(entry)
            if (state.activated && !state.maintenance) {
                check(app.engine.dpm.isLockTaskPermitted(entry.packageName)) { "This app is not approved" }
                startActivity(intent, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
            } else startActivity(intent)
        }.onFailure { message = "${entry.label} is unavailable. ${it.message.orEmpty()}" }
    }
    private fun openSettings(action: String = Settings.ACTION_SETTINGS) {
        runCatching {
            if (screen == "admin") app.auth.requireSession(session ?: "")
            if (state.activated) {
                app.auth.requireSession(session ?: "")
                check(state.maintenance) { "Enter maintenance first" }
            }
            startExpectedAdminActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                if (action == Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) data = android.net.Uri.parse("package:$packageName")
            })
        }.onFailure { message = it.message ?: "Settings unavailable" }
    }

    @Composable private fun Content() {
        BackHandler {
            if (screen == "admin" && adminPage != "overview") {
                if (adminPage == "security") cancelTotpEnrollment()
                adminPage = "overview"
            }
            else { recoveryCode = ""; navigate("home") }
        }
        when (screen) {
            "home" -> Home()
            "controls" -> Controls()
            "wifi" -> Page("Wi-Fi") { WifiControls() }
            "homeApps" -> HomeApps()
            "login", "setup", "recover" -> Credentials()
            "adminUnavailable" -> AdministratorUnavailable()
            "admin" -> Admin()
        }
    }
    @Composable private fun Notice() {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 12.dp).testTag("message"), color = MaterialTheme.colorScheme.error)
    }
    @Composable private fun Home() {
        var now by remember { mutableStateOf(LocalDateTime.now()) }
        LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); refreshRingerMode(); delay(1_000) } }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("L U C Y", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Text(state.status, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).testTag("status"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    changeRingerMode(when (ringerMode) {
                        AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                        AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                        else -> AudioManager.RINGER_MODE_NORMAL
                    })
                }, modifier = Modifier.size(48.dp).testTag("quickSoundMode")) {
                    Icon(painterResource(when (ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_sound_off
                        AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_vibrate
                        else -> R.drawable.ic_sound_on
                    }), contentDescription = "${ringerLabel()} mode. Switch to ${when (ringerMode) {
                        AudioManager.RINGER_MODE_NORMAL -> "Vibrate"
                        AudioManager.RINGER_MODE_VIBRATE -> "Silent"
                        else -> "Sound"
                    }}",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(now.format(DateTimeFormatter.ofPattern("h:mm")), fontSize = 76.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp)
            Text(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            if (state.administratorEntry in setOf(AdministratorEntry.SETUP, AdministratorEntry.LOGIN) && !state.activated) {
                OutlinedButton(enabled = !busy, onClick = { adminPage = "overview"; openAdministration() }) {
                    Text(if (state.owner) "Continue phone setup" else "Set up Lucy preview")
                }
            }
            if (state.maintenance || state.temporaryInternet) Text(
                if (state.temporaryInternet) "Unfiltered setup internet · ${state.internetMinutes} min left. Keep administrator custody."
                else "Maintenance · ${state.maintenanceMinutes} min left. Return to Administration to finish.",
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                state.apps.forEach { entry ->
                    Text(entry.label, fontSize = 28.sp, fontWeight = FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { openApp(entry) }.padding(vertical = 9.dp).testTag("app:${entry.packageName}"))
                }
                if (state.apps.isEmpty()) Text(if (state.administratorEntry == AdministratorEntry.LOADING)
                    "Loading your saved setup…" else "Your approved apps will appear here.", Modifier.padding(vertical = 24.dp))
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { navigate("controls") }) { Text("Phone controls") }
                TextButton(enabled = !busy && state.administratorEntry != AdministratorEntry.LOADING,
                    onClick = ::openAdministration, modifier = Modifier.testTag("administrationEntry")) {
                    Text(if (state.administratorEntry == AdministratorEntry.LOADING) "Loading administration…" else "Administration")
                }
            }
            if (!state.activated) Text("A little more room for what matters.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            Notice()
        }
    }
    @Composable private fun HomeApps() {
        val selected = state.apps.map { it.packageName }.toSet()
        Page("Home screen apps", "controls") {
            Text("Choose which administrator-approved apps appear on Lucy Home. Hiding an app does not uninstall it or revoke its approved access.")
            Spacer(Modifier.height(12.dp))
            state.allowedApps.forEach { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = entry.packageName in selected, enabled = !busy && !entry.essential,
                        onCheckedChange = { checked ->
                            val next = if (checked) selected + entry.packageName else selected - entry.packageName
                            work { app.engine.saveHomeSelection(next); "Home apps updated" }
                        }, modifier = Modifier.testTag("homeApp:${entry.packageName}"))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(entry.label, fontWeight = FontWeight.Medium)
                        Text(if (entry.essential) "Required phone function" else if (entry.packageName in selected) "Shown on Home" else "Hidden from Home", fontSize = 12.sp)
                    }
                }
            }
            if (state.allowedApps.none { !it.essential }) Text("Your administrator has not approved any optional apps yet.", modifier = Modifier.padding(top = 12.dp))
            Text("Need another app? Ask your administrator to add it from Administrator tools.", fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
    @Composable private fun Page(title: String, backScreen: String = "home", content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            TextButton(onClick = { recoveryCode = ""; navigate(backScreen) }) {
                Text(if (backScreen == "home") "← Home" else "← Phone controls")
            }
            Text(title, fontSize = 32.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 18.dp))
            Notice()
            content()
        }
    }
    @Composable private fun Credentials() {
        if (recoveryCode.isBlank()) {
            if (state.administratorEntry == AdministratorEntry.LOADING) {
                Page("Checking administration") { Text("Loading your saved setup…") }
                return
            }
            if (state.administratorEntry == AdministratorEntry.UNAVAILABLE) {
                AdministratorUnavailable()
                return
            }
        }
        var pin by remember(screen) { mutableStateOf("") }
        var confirm by remember(screen) { mutableStateOf("") }
        var code by remember(screen) { mutableStateOf("") }
        var useBackupPin by remember(screen, state.totpConfigured) { mutableStateOf(false) }
        Page(when (screen) { "setup" -> "Set up administration"; "recover" -> "Recover access"; else -> "Administrator access" }) {
            if (recoveryCode.isNotBlank()) {
                Text("Store this recovery code offline. It will only be shown once and can reset the administrator PIN.")
                Text(recoveryCode, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 24.dp).testTag("recoveryCode"))
                Button(onClick = { recoveryCode = ""; navigate("login") }) { Text("I have saved the code") }
            } else {
                val authenticatorLogin = screen == "login" && state.totpConfigured && !useBackupPin
                Text(when {
                    authenticatorLogin -> "Enter the current six-digit code from the enrolled authenticator. The backup PIN is available only through the explicit option below."
                    screen == "login" -> "Enter the backup administrator PIN to allow apps, open setup tools, or change device policy."
                    else -> "Use a separate PIN with at least eight digits. The daily screen-lock PIN is different."
                })
                Spacer(Modifier.height(20.dp))
                if (screen == "recover") OutlinedTextField(code, { code = it }, label = { Text("Recovery code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (authenticatorLogin) OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) },
                    label = { Text("Authenticator code") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("totp"))
                else OutlinedTextField(pin, { pin = it.take(64) }, label = { Text(if (screen == "login") "Backup administrator PIN" else "New administrator PIN") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pin"))
                if (screen != "login") OutlinedTextField(confirm, { confirm = it.take(64) }, label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("confirmPin"))
                Button(enabled = !busy, modifier = Modifier.padding(top = 20.dp).testTag("credentialSubmit"), onClick = {
                    val mode = screen; val enteredPin = pin; val enteredCode = code
                    if (mode != "login" && pin != confirm) { message = "PINs do not match" }
                    else {
                        pin = ""; confirm = ""; code = ""
                        work(success = { value -> if (mode == "login") { session = value; navigate("admin") } else recoveryCode = value }) {
                            when (mode) {
                                "setup" -> app.auth.setup(enteredPin)
                                "recover" -> app.auth.recover(enteredCode, enteredPin)
                                else -> if (authenticatorLogin) app.auth.unlockTotp(enteredCode) else app.auth.unlock(enteredPin)
                            }
                        }
                    }
                }) { Text(if (screen == "login") "Unlock" else "Save PIN") }
                if (screen == "login" && state.totpConfigured) TextButton(enabled = !busy, onClick = {
                    pin = ""; code = ""; useBackupPin = !useBackupPin
                }) { Text(if (useBackupPin) "Use authenticator code" else "Use backup PIN") }
                if (screen == "login") TextButton(enabled = !busy, onClick = { navigate("recover") }) { Text("Use recovery code") }
            }
        }
    }
    @Composable private fun AdministratorUnavailable() {
        Page("Administrator access unavailable") {
            Text("Lucy could not safely confirm the saved administrator setup. This does not mean a new PIN is needed. Nothing has been reset.")
            Text("Unlock the Android screen first, then retry. If this persists, keep the phone with its administrator and investigate the saved configuration; do not clear app storage.", Modifier.padding(vertical = 16.dp))
            OutlinedButton(enabled = !busy, onClick = ::refresh) { Text("Retry saved setup") }
        }
    }
    private fun adminTool(intent: Intent) {
        runCatching {
            app.auth.requireSession(session ?: "")
            check(!app.store.configured() || app.engine.inMaintenance()) { "Start supervised maintenance to open setup tools" }
            startExpectedAdminActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { message = it.message ?: "This setup tool is not installed or available on this device" }
    }
    @Composable private fun Admin() {
        Administration(
            state = state, page = adminPage, busy = busy, message = message,
            forbidden = remember(state.catalog) { app.engine.inventory.forbidden() },
            totpEnrollmentUri = totpEnrollmentUri,
            totpSetupKey = totpSetupKey,
            onPage = { if (adminPage == "security" && it != "security") cancelTotpEnrollment(); adminPage = it; message = "" },
            onHome = { navigate("home") },
            onLock = { lockAdministration(); navigate("home") },
            actions = AdminActions(
                select = { selected -> work { app.engine.saveDraft(session ?: "", selected); "Draft saved. Daily apps are unchanged until you apply it." } },
                acknowledge = { key, value -> work { app.engine.acknowledge(session ?: "", key, value); "Checklist saved" } },
                maintenance = { work(success = {
                    if (isFinishing || isDestroyed) {
                        app.startActivity(Intent(app, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    } else {
                        ensureLockTask()
                        adminPage = "connect"
                        message = it
                    }
                }) {
                    app.engine.beginMaintenance(session ?: "") {
                        val exit = FutureTask<Unit> {
                            app.adminReturnPage = "connect"
                            if (app.engine.lockTaskRunning()) {
                                app.prepareAdminActivityHandoff(session ?: "", "connect")
                                expectAdminToolPause()
                                stopLockTask()
                            }
                        }
                        runOnUiThread(exit)
                        exit.get(5, TimeUnit.SECONDS)
                    }
                    "Setup tools are available for 30 minutes"
                } },
                finish = { work { app.engine.finishMaintenance(); "Daily restrictions restored. Check filtering before handoff." } },
                temporaryInternet = { work { app.engine.beginTemporaryInternet(session ?: "", true); "Unfiltered internet enabled for up to 15 minutes" } },
                filtering = { work { app.engine.prepareFiltering(session ?: ""); "Filtering check completed; review the results below" } },
                apply = { work(success = { adminPage = "overview"; message = it }) {
                    app.engine.applyDraft(session ?: ""); "App list applied. Review current status before handing over the phone."
                } },
                settings = ::openSettings,
                browser = { url -> adminTool(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                    .apply { if (app.engine.inventory.exists("com.android.chrome")) setPackage("com.android.chrome") }) },
                play = { pkg -> adminTool(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    if (pkg == null) "market://search?q=apps" else "market://details?id=$pkg")).setPackage("com.android.vending")) },
                openApp = { entry -> adminTool(app.engine.inventory.launchIntent(entry)) },
                rotatePin = { pin -> work(success = { recoveryCode = it; session = null; navigate("login") }) {
                    app.auth.changePin(session ?: "", pin)
                } },
                beginTotp = { workValue(success = { enrollment ->
                    totpEnrollmentUri = enrollment.uri
                    totpSetupKey = enrollment.setupKey
                    message = "Scan the QR code and verify a current code"
                }) { app.auth.beginTotpEnrollment(session ?: "") } },
                confirmTotp = { code -> work(success = {
                    totpEnrollmentUri = ""
                    totpSetupKey = ""
                    message = it
                }) { app.auth.confirmTotpEnrollment(session ?: "", code); "Authenticator enabled" } },
                disableTotp = { work { app.auth.disableTotp(session ?: ""); totpEnrollmentUri = ""; totpSetupKey = ""; "Authenticator disabled; use the backup PIN" } },
                erase = { phrase -> work { app.engine.decommission(session ?: "", phrase); "Reset requested" } },
                refresh = ::refresh,
            ),
        )
    }
    @Composable private fun Controls() {
        val audio = remember { getSystemService(AudioManager::class.java) }
        var brightness by remember { mutableFloatStateOf(window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f) }
        Page("Phone controls") {
            LaunchedEffect(Unit) { while (true) { refreshRingerMode(); delay(1_000) } }
            Text("Appearance", fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceMode.entries.forEach { mode ->
                    FilterChip(selected = appearanceMode == mode, onClick = {
                        app.appearance.set(mode)
                        appearanceMode = mode
                    }, label = { Text(mode.label) }, modifier = Modifier.testTag("appearance:${mode.name}"))
                }
            }
            Text("System follows Android's light or dark appearance. This choice applies only to Lucy screens.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
            Text("Sound mode: ${ringerLabel()}", modifier = Modifier.testTag("soundMode"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sound" to AudioManager.RINGER_MODE_NORMAL, "Vibrate" to AudioManager.RINGER_MODE_VIBRATE,
                    "Silent" to AudioManager.RINGER_MODE_SILENT).forEach { (label, mode) ->
                    OutlinedButton(onClick = { changeRingerMode(mode) }, enabled = ringerMode != mode,
                        modifier = Modifier.weight(1f).testTag("sound:$label")) { Text(label) }
                }
            }
            Text("Silent mutes calls and notifications without vibration. Alarm and media volume are unchanged.", fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp))
            if (!getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)
                Text("One-time sound-mode permission may be required. An administrator can allow it in Security & recovery.", fontSize = 12.sp)
            Text("Launcher brightness")
            Slider(value = brightness, onValueChange = { value -> brightness = value; window.attributes = window.attributes.apply { screenBrightness = value.coerceAtLeast(0.05f) } })
            Text("Brightness applies to the launcher. System brightness is available in supervised maintenance.", fontSize = 12.sp)
            Text("Home screen", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            OutlinedButton(onClick = { navigate("homeApps") }, modifier = Modifier.testTag("homeAppControls")) { Text("Choose Home screen apps") }
            Text("Show or hide apps your administrator has already approved.", fontSize = 12.sp)
            OutlinedButton(onClick = { navigate("wifi") }, modifier = Modifier.testTag("wifiControls")) { Text("Wi-Fi") }
            Row(Modifier.padding(vertical = 20.dp)) {
                OutlinedButton(onClick = { runCatching { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }.onFailure { message = "Change Do Not Disturb settings during maintenance" } }) { Text("Volume −") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { runCatching { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }.onFailure { message = "Change Do Not Disturb settings during maintenance" } }) { Text("Volume +") }
            }
            if (!state.activated) OutlinedButton(onClick = { openSettings(Settings.ACTION_HOME_SETTINGS) }) { Text("Set Lucy as Home") }
            if (state.maintenance) Button(onClick = { work { app.engine.finishMaintenance(); "Daily restrictions restored" } }) { Text("End maintenance now") }
        }
    }
}
