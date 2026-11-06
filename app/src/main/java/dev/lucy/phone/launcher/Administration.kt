package dev.lucy.phone.launcher

import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lucy.phone.configuration.AppEntry
import dev.lucy.phone.configuration.Onboarding
import dev.lucy.phone.configuration.PolicyRules
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

data class AdminActions(
    val select: (Set<String>) -> Unit, val acknowledge: (String, Boolean) -> Unit,
    val maintenance: () -> Unit, val finish: () -> Unit, val temporaryInternet: () -> Unit,
    val filtering: () -> Unit, val apply: () -> Unit, val settings: (String) -> Unit,
    val browser: (String) -> Unit, val play: (String?) -> Unit, val openApp: (AppEntry) -> Unit,
    val rotatePin: (String) -> Unit, val beginTotp: () -> Unit, val confirmTotp: (String) -> Unit,
    val disableTotp: () -> Unit, val erase: (String) -> Unit, val refresh: () -> Unit,
)

@Composable private fun Heading(text: String) {
    Text(text, fontSize = 21.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
}
@Composable private fun ConfirmationRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}

@Composable fun Administration(state: HomeState, page: String, busy: Boolean, message: String,
    forbidden: Set<String>, totpEnrollmentUri: String, totpSetupKey: String, onPage: (String) -> Unit,
    onHome: () -> Unit, onLock: () -> Unit, actions: AdminActions) {
    val pages = if (state.activated) linkedMapOf("apps" to "Allowed apps", "connect" to "System settings & accounts",
        "filter" to "Filtering & safety", "review" to "Review changes")
    else linkedMapOf("connect" to "1 · Connect & accounts", "apps" to "2 · Allow & set up apps",
        "filter" to "3 · Test filtering", "review" to "4 · Review & finish")
    val selected = state.draftApps.map { it.packageName }.toSet()
    val tools = (!state.activated || state.maintenance) && !busy
    var dialog by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }
    var showTotpSetupKey by remember(totpEnrollmentUri) { mutableStateOf(false) }
    var eraseText by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = if (page == "overview") onHome else ({ onPage("overview") })) {
                Text(if (page == "overview") "← Home" else "← Administration")
            }
            if (state.maintenance) TextButton(enabled = !busy, onClick = { dialog = "finish" }) { Text("End session") }
        }
        if (state.temporaryInternet) Text("UNFILTERED INTERNET · ${state.internetMinutes} min left",
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp).testTag("internetWarning"))
        else if (state.maintenance) Text("SUPERVISED MAINTENANCE · ${state.maintenanceMinutes} min left",
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp).testTag("message"))
        key(page) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(pages[page] ?: when (page) { "security" -> "Security & recovery"; "details" -> "Diagnostics"; else -> "Administration" },
                    fontSize = 29.sp, modifier = Modifier.padding(vertical = 16.dp))
                if (page == "overview") {
                    Text(state.status, fontWeight = FontWeight.Medium)
                    Text(if (state.activated) "Administrator tools control which apps are allowed, system setup, filtering, and recovery. Home-screen visibility can be changed without administrator authentication from Phone controls."
                        else "Set up the phone before restricting it. Connect, choose what belongs here, sign in, and test before enabling daily mode.",
                        modifier = Modifier.padding(vertical = 12.dp))
                    if (!state.owner) Text("Launcher preview only: no device-wide restrictions or enforced filtering. Device Owner enrollment must happen before adding accounts on a managed test device.")
                    if (state.activated && !state.maintenance) Button(enabled = !busy, onClick = { dialog = "maintenance" }) { Text("Open supervised setup") }
                    if (state.maintenance) Button(enabled = !busy, onClick = { onPage("connect") }) { Text("Continue supervised setup") }
                    pages.forEach { (id, title) ->
                        OutlinedButton(onClick = { onPage(id) }, modifier = Modifier.fillMaxWidth().testTag("admin:$id")) { Text(title) }
                    }
                    TextButton(onClick = { onPage("security") }) { Text("Security & recovery") }
                    TextButton(onClick = { onPage("details") }) { Text("Diagnostics & policy log") }
                    TextButton(onClick = onLock) { Text("Lock administration") }
                    Text("Authenticator codes and the backup administrator PIN are separate from the Android screen-lock PIN. For 30 seconds after the last interaction, returning to Administration does not require another code. Lock administration before handing the phone back. Expiry, recreation, and process restart also lock it. Draft choices are retained.", fontSize = 13.sp)
                } else {
                    if (page in setOf("connect", "apps", "filter") && state.activated && !state.maintenance) {
                        Text("Daily mode keeps Settings, Play, and unapproved browsers closed. Open a supervised session to use setup tools. Chrome can be selected separately for daily use.", fontSize = 13.sp)
                        Button(enabled = !busy, onClick = { dialog = "maintenance" }) { Text("Open supervised setup") }
                    }
                    when (page) {
                        "connect" -> {
                            Text(state.network)
                            Heading("Connect before signing in")
                            Text("Start on Wi-Fi or cellular that has working internet. Adding Google and app accounts is supported; separate Android users, corporate work profiles, and required corporate VPNs are not supported in this version.")
                            OutlinedButton(enabled = tools, onClick = { actions.settings(Settings.ACTION_WIFI_SETTINGS) }) { Text("Wi-Fi settings") }
                            OutlinedButton(enabled = tools, onClick = { actions.settings(Settings.ACTION_SYNC_SETTINGS) }) { Text("Accounts & sync") }
                            OutlinedButton(enabled = tools, onClick = { actions.settings(Settings.ACTION_SETTINGS) }) { Text("Open system Settings") }
                            OutlinedButton(enabled = tools, onClick = { actions.browser("https://example.com") }) { Text("Open Chrome / browser") }
                            Heading("If filtered DNS blocks internet access")
                            Text("Cloudflare-only filtering needs DNS-over-TLS on port 853. The optional local filter uses encrypted DNS over HTTPS. Temporary unfiltered internet is unavailable while the local filter is installed.")
                            if (state.owner) OutlinedButton(enabled = tools, onClick = { dialog = "internet" }) { Text("Temporary internet · 15 minutes") }
                            if (state.temporaryInternet) OutlinedButton(enabled = !busy, onClick = actions.filtering) { Text("End temporary internet & test filtering") }
                            Text("If the timer is not allowed yet, open Security & recovery below. A failed filtering check keeps initial setup unlocked; it never counts an offline connection as a pass.", fontSize = 13.sp)
                            TextButton(onClick = { onPage("security") }) { Text("Security & recovery") }
                            Button(onClick = { onPage("apps") }) { Text("Next: choose apps") }
                        }
                        "apps" -> {
                            Text("${selected.size} apps in the administrator allowlist draft. Phone essentials stay allowed. Start small, then add only the tools this person needs.")
                            Text("These checkboxes control which apps may run in daily mode; they do not control Home layout. Use Phone controls → Choose Home screen apps to show or hide approved apps. Installing, allowing, and signing in are separate steps.", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                            OutlinedButton(enabled = tools, onClick = { actions.play(null) }) { Text("Install or update in Google Play") }
                            OutlinedButton(enabled = !busy, onClick = actions.refresh) { Text("Refresh installed apps") }
                            OutlinedTextField(search, { search = it }, label = { Text("Search installed apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            val catalog = state.catalog.filter { it.packageName !in forbidden && (search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true)) }
                            catalog.forEach { entry ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(entry.packageName in selected, enabled = !busy && !entry.essential,
                                        onCheckedChange = { checked -> actions.select(if (checked) selected + entry.packageName else selected - entry.packageName) },
                                        modifier = Modifier.testTag("select:${entry.packageName}"))
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(entry.label, fontWeight = FontWeight.Medium)
                                        Text(if (entry.essential) "Required phone function" else if (entry.packageName in selected) "Allowed in daily mode" else "Not allowed in daily mode", fontSize = 12.sp)
                                        Text(entry.packageName, fontSize = 10.sp)
                                        if (entry.packageName == PolicyRules.CHROME_PACKAGE) Text("Optional web access. DNS filtering does not screen every page; custom DNS can bypass it.", fontSize = 12.sp)
                                    }
                                    TextButton(enabled = tools, onClick = { actions.openApp(entry) }) { Text("Set up") }
                                }
                            }
                            if (catalog.isEmpty()) Text("No matching installed apps. Install an app in Play, then refresh.")
                            Heading("Optional starting points")
                            Onboarding.suggestions.forEach { suggestion ->
                                Text(suggestion.label, fontWeight = FontWeight.Medium)
                                Text(suggestion.purpose, fontSize = 13.sp)
                                TextButton(enabled = tools, onClick = { actions.play(suggestion.packageName) }) {
                                    Text(if (state.catalog.any { it.packageName == suggestion.packageName }) "View in Play" else "Find in Play · not installed")
                                }
                            }
                            Heading("Before moving on")
                            Text("Use Set up to sign into each selected app, choose the right account/workspace, allow its needed permissions, and test its normal tasks. Check links and embedded content: DNS cannot filter everything inside an app.")
                            ConfirmationRow("I checked the selected apps, accounts, and permissions", state.appsReviewed, !busy) { actions.acknowledge("appsReviewed", it) }
                            Heading("Setup-only tools")
                            Text("Google Play, unrestricted Settings, and browsers other than Chrome are setup-only. Chrome is an optional allowed app: approve it above, review the draft, then apply. Removing it from the allowlist blocks it again in managed daily mode.", fontSize = 13.sp)
                            Button(onClick = { onPage("filter") }) { Text("Next: test filtering") }
                        }
                        "filter" -> {
                            Text(state.network)
                            Text("Run checks here—Chrome is not required. Lucy tests normal HTTPS and harmless provider fixtures. With the local filter installed, it also verifies a reserved test domain and its subdomain are blocked.", modifier = Modifier.padding(vertical = 12.dp))
                            Button(enabled = state.owner && !busy, onClick = actions.filtering, modifier = Modifier.testTag("testFiltering")) {
                                Text(if (state.temporaryInternet) "Restore filtering & run checks" else "Enable filtering & run checks")
                            }
                            if (!state.owner) Text("Enforced filtering tests require Device Owner mode. You can explore the rest of onboarding in launcher preview.")
                            Heading(if (state.filterPassed) "Filtering verified on this network" else "Filtering not verified")
                            Text(state.filterDetail)
                            state.filterChecks.forEach { check ->
                                Text("${if (check.passed) "PASS" else "NOT VERIFIED"} · ${check.label}", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                                Text(check.detail, fontSize = 13.sp)
                            }
                            if (!state.filterPassed) Text("If normal internet fails, switch networks or return to Connect & accounts for temporary setup internet. Daily mode cannot be enabled for the first time until all checks pass.", modifier = Modifier.padding(vertical = 12.dp))
                            Heading("Optional browser cross-check")
                            Text("During supervised setup, open the links below. example.com should load; both category sites should fail. Turn off Chrome's custom Secure DNS for this test, or it may use a different resolver. Browser errors alone do not prove filtering.", fontSize = 13.sp)
                            listOf("Allowed website" to "https://example.com", "Adult-category test" to "https://nudity.testcategory.com", "Malware test" to "https://malware.testcategory.com").forEach { (label, url) ->
                                OutlinedButton(enabled = tools && !state.temporaryInternet, onClick = { actions.browser(url) }) { Text(label) }
                            }
                            Text("The local filter combines HaGeZi NSFW with administrator domain rules and Cloudflare Families upstream. Domain filtering cannot inspect messages, videos, or generated content inside allowed apps. Custom encrypted DNS and direct-IP traffic remain bypass risks. Results expire after five minutes and on network changes.", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                            Button(onClick = { onPage("review") }) { Text("Next: review & finish") }
                        }
                        "review" -> {
                            Text("Allowed apps", fontWeight = FontWeight.Medium)
                            Text(state.draftApps.joinToString(" · ") { it.label })
                            if (PolicyRules.CHROME_PACKAGE in selected) {
                                Heading("Chrome is included")
                                Text("Chrome will be allowed in daily mode and initially shown on Home. Lucy keeps filtered Private DNS enabled, but does not lock Chrome's own DNS settings or screen all web content. Review Secure DNS and cross-check the test sites in Chrome before handoff.", fontSize = 13.sp)
                            }
                            Heading("Handoff checklist")
                            ConfirmationRow("I checked the selected apps, accounts, and permissions", state.appsReviewed, !busy) { actions.acknowledge("appsReviewed", it) }
                            ConfirmationRow("The administrator recovery code is stored safely offline", state.recoverySaved, !busy) { actions.acknowledge("recoverySaved", it) }
                            ConfirmationRow("I tested visible Home / Back buttons from another app", state.navigationTested, !busy) { actions.acknowledge("navigationTested", it) }
                            Text("Use three-button navigation on the current emulator. Gesture navigation can try to open Pixel Launcher during lock task. Configure this in Android Settings before daily use.", fontSize = 13.sp)
                            if (!state.activated && state.owner && state.blockers.isNotEmpty()) {
                                Heading("Still needed")
                                state.blockers.forEach { Text("• $it", modifier = Modifier.padding(vertical = 4.dp)) }
                            }
                            TextButton(onClick = { onPage("security") }) { Text("Screen lock, timer & recovery") }
                            TextButton(onClick = { onPage("filter") }) { Text("Recheck filtering") }
                            Heading("What will change")
                            Text("Lucy becomes the daily Home screen. Allowed apps, including Chrome if approved, remain available and newly approved apps start on Home. Settings, stores, unapproved browsers, and installation routes close. Temporary internet ends. Administrator access stays available.")
                            Button(enabled = !busy && (!state.owner || (state.appsReviewed && (state.activated || state.blockers.isEmpty()))),
                                onClick = { dialog = "apply" }, modifier = Modifier.testTag("applyDaily")) {
                                Text(if (!state.owner) "Save launcher preview" else if (state.activated) "Apply draft & return to daily mode" else "Enable daily mode")
                            }
                            if (state.activated) Text("If verification fails, restrictions are still restored and Attention required remains visible. Keep administrator custody until internet, filtering, and essential phone functions pass.", fontSize = 13.sp)
                            if (state.maintenance) OutlinedButton(enabled = !busy, onClick = { dialog = "finish" }) { Text("End session without applying draft") }
                        }
                        "security" -> {
                            Text("Android screen lock protects everyday access. Authenticator codes normally protect Lucy management; the administrator PIN remains an explicit backup. They are separate credentials.")
                            OutlinedButton(enabled = tools, onClick = { actions.settings(Settings.ACTION_SECURITY_SETTINGS) }) { Text("Set Android screen lock") }
                            OutlinedButton(enabled = tools, onClick = { actions.settings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }) { Text("Allow Lucy sound-mode access") }
                            if (Build.VERSION.SDK_INT >= 31) OutlinedButton(enabled = tools, onClick = { actions.settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }) { Text("Allow maintenance timer") }
                            if (!tools && !busy) Text("Open supervised setup from Administration to change Android settings.")
                            ConfirmationRow("The recovery code is stored offline", state.recoverySaved, !busy) { actions.acknowledge("recoverySaved", it) }
                            Heading("Authenticator access")
                            if (state.totpConfigured) {
                                Text("Enabled. Administration opens with a current six-digit authenticator code. Each code can be accepted only once; the backup PIN remains available from the login screen.")
                                OutlinedButton(enabled = !busy, onClick = actions.beginTotp) { Text("Replace authenticator") }
                                TextButton(enabled = !busy, onClick = { dialog = "disableTotp" }) { Text("Disable authenticator") }
                            } else if (totpEnrollmentUri.isBlank()) {
                                Text("Enroll a trusted administrator's authenticator app. Setup is not enabled until a code from the new entry is verified.")
                                Button(enabled = !busy, onClick = actions.beginTotp) { Text("Set up authenticator") }
                            }
                            if (totpEnrollmentUri.isNotBlank()) {
                                Text("Scan this one-time QR code with a standard authenticator app, then enter its current code below. Leaving or locking Administration cancels this setup.",
                                    fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                                val qr = remember(totpEnrollmentUri) {
                                    val matrix = QRCodeWriter().encode(totpEnrollmentUri, BarcodeFormat.QR_CODE, 640, 640,
                                        mapOf(EncodeHintType.MARGIN to 2))
                                    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                                        val pixels = IntArray(matrix.width * matrix.height) { index ->
                                            if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                                        }
                                        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
                                    }.asImageBitmap()
                                }
                                Image(qr, "Authenticator enrollment QR code", Modifier.size(240.dp).testTag("totpEnrollmentQr"))
                                TextButton(onClick = { showTotpSetupKey = !showTotpSetupKey }) {
                                    Text(if (showTotpSetupKey) "Hide setup key" else "Can’t scan? Show setup key")
                                }
                                if (showTotpSetupKey) {
                                    Text("Anyone with this key can generate administrator codes. Send it only to the intended administrator through a trusted encrypted channel. It disappears when you leave or lock Administration.",
                                        color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                    Text(totpSetupKey, fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("totpSetupKey"))
                                    Text("Account: Lucy:Administrator · Time based · 6 digits · SHA-1 · 30 seconds",
                                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedTextField(totpCode, { totpCode = it.filter { digit -> digit in '0'..'9' }.take(6) },
                                    label = { Text("Six-digit code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("totpEnrollmentCode"))
                                Button(enabled = !busy && totpCode.length == 6, onClick = {
                                    val code = totpCode; totpCode = ""; actions.confirmTotp(code)
                                }, modifier = Modifier.testTag("confirmTotp")) { Text("Verify & enable") }
                            }
                            Heading("Change backup administrator PIN")
                            OutlinedTextField(newPin, { newPin = it.take(64) }, label = { Text("New backup PIN · at least eight digits") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                            OutlinedTextField(confirmPin, { confirmPin = it.take(64) }, label = { Text("Confirm new backup PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                            Text("This also replaces the recovery code. The new code is shown once.", fontSize = 13.sp)
                            OutlinedButton(enabled = !busy && newPin.length >= 8 && newPin == confirmPin, onClick = {
                                val pin = newPin; newPin = ""; confirmPin = ""; actions.rotatePin(pin)
                            }) { Text("Replace backup PIN & recovery code") }
                            if (state.maintenance) TextButton(onClick = { dialog = "erase" }) { Text("Decommission device…") }
                        }
                        "details" -> {
                            Text(state.baseline, fontSize = 12.sp)
                            Heading("Policy status")
                            Text(state.status)
                            state.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                            Heading("System dependencies")
                            Text("These support the phone and approved apps. They are not automatically visible daily apps.", fontSize = 13.sp)
                            state.support.forEach { (pkg, reason) -> Text("$pkg — $reason", fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                            Heading("Recent events")
                            state.log.forEach { Text(it, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                        }
                    }
                }
            }
        }
    }
    if (dialog.isNotEmpty()) {
        val title = when (dialog) { "internet" -> "Use unfiltered setup internet?"; "maintenance" -> "Open supervised setup?"; "apply" -> "Apply this app allowlist?"; "disableTotp" -> "Disable authenticator access?"; "erase" -> "Erase this device?"; else -> "End this session?" }
        val detail = when (dialog) {
            "internet" -> "Content filtering will be unavailable for up to 15 minutes, or until maintenance ends. Keep physical custody. Strict filtered DNS is restored on expiry, restart, or when you test/finish. If the provider remains unreachable, internet may stop again. This cannot make a daily-use filtering test pass."
            "maintenance" -> "For up to 30 minutes, you can install apps, sign in, and open Settings and browsers. Keep physical custody. Filtering stays enabled unless you separately confirm temporary internet."
            "apply" -> "Apply the displayed app draft and close setup tools. Keep the phone until the resulting status and essential phone tests are verified."
            "disableTotp" -> "Future Administration access will use the backup administrator PIN until a new authenticator is enrolled."
            "erase" -> "This deletes all device data. Back up and prepare Google account / Factory Reset Protection credentials first. Type ERASE THIS DEVICE to confirm."
            else -> "Restore the current daily app policy and filtered DNS now. Your unapplied draft is retained."
        }
        AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(title) }, text = {
            Column { Text(detail); if (dialog == "erase") OutlinedTextField(eraseText, { eraseText = it }, label = { Text("Confirmation") }) }
        }, confirmButton = {
            TextButton(enabled = !busy && (dialog != "erase" || eraseText == "ERASE THIS DEVICE"), onClick = {
                val action = dialog; dialog = ""
                when (action) { "internet" -> actions.temporaryInternet(); "maintenance" -> actions.maintenance(); "apply" -> actions.apply(); "disableTotp" -> actions.disableTotp(); "erase" -> actions.erase(eraseText); else -> actions.finish() }
            }) { Text(if (dialog == "erase") "Erase everything" else "Confirm") }
        }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    }
}
