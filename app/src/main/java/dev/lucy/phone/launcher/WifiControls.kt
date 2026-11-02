@file:Suppress("DEPRECATION") // Device Owner is exempt from legacy Wi-Fi API restrictions.

package dev.lucy.phone.launcher

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.lucy.phone.LucyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class WifiRow(val name: String, val security: String, val savedId: Int? = null, val hidden: Boolean = false)
private data class WifiState(val enabled: Boolean = false, val scanningEnabled: Boolean = false,
    val connected: String? = null, val rows: List<WifiRow> = emptyList())

/** Narrow daily controls: no Settings intents, policy relaxation, proxy or DNS edits. */
@Composable
fun WifiControls() {
    val context = LocalContext.current
    val app = context.applicationContext as LucyApp
    val wifi = remember { app.getSystemService(WifiManager::class.java) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(WifiState()) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<WifiRow?>(null) }
    var password by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var manualOpen by remember { mutableStateOf(false) }

    fun action(block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            message = result.getOrElse { it.message ?: "Wi-Fi operation failed" }
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        val permissions = withContext(Dispatchers.IO) { runCatching {
            check(app.engine.isOwner) { "Wi-Fi controls require a managed Lucy device" }
            val required = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) +
                if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList()
            required.forEach { permission ->
                check(app.engine.dpm.setPermissionGrantState(app.engine.admin, app.packageName, permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)) { "Wi-Fi permission could not be enabled" }
            }
        } }
        if (permissions.isFailure) { message = permissions.exceptionOrNull()?.message.orEmpty(); return@LaunchedEffect }
        ready = true
        while (true) {
            val result = withContext(Dispatchers.IO) { runCatching { readWifi(context, wifi) } }
            result.onSuccess { state = it }.onFailure { message = it.message ?: "Unable to read Wi-Fi" }
            delay(2_000)
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(if (!state.enabled) "Wi-Fi is off" else state.connected?.let { "Connected to $it" } ?: "Not connected",
        modifier = Modifier.testTag("wifiStatus"))
    OutlinedButton(enabled = ready && !busy, onClick = { action {
        check(wifi.setWifiEnabled(!state.enabled)) { "Android could not change Wi-Fi" }
        "Wi-Fi change requested"
    } }) { Text(if (state.enabled) "Turn Wi-Fi off" else "Turn Wi-Fi on") }
    if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 8.dp).testTag("wifiMessage"))
    if (state.enabled && ready) {
        if (!state.scanningEnabled) {
            Text("Android requires Location to find nearby networks. Lucy uses it here only to list Wi-Fi networks.")
            OutlinedButton(enabled = !busy, onClick = { action {
                app.engine.dpm.setLocationEnabled(app.engine.admin, true)
                wifi.startScan()
                "Location enabled for nearby network scanning"
            } }) { Text("Enable nearby network scanning") }
        } else {
            OutlinedButton(enabled = !busy, onClick = { action {
                if (wifi.startScan()) "Looking for networks…" else "Showing recent networks. Android limits how often scans can run."
            } }) { Text("Refresh networks") }
        }
        state.rows.forEach { row ->
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && row.name != state.connected && row.security != "Administrator setup required",
                onClick = {
                    if (row.savedId != null) action {
                        check(wifi.enableNetwork(row.savedId, true)) { "Android could not connect to this saved network" }
                        "Connecting to ${row.name}…"
                    } else { selected = row; password = "" }
                }) { Text("${row.name} · ${if (row.savedId != null) "Saved" else row.security}") }
        }
        if (state.rows.isEmpty()) Text("No networks found yet. Refresh or enter a network name below.")
        OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("Network name (SSID)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row { Checkbox(checked = manualOpen, onCheckedChange = { manualOpen = it }); Text("Open network (no password)", Modifier.padding(top = 12.dp)) }
        OutlinedButton(enabled = manualName.isNotBlank() && !busy, onClick = {
            selected = WifiRow(manualName, if (manualOpen) "Open" else "WPA2/WPA3", hidden = true); password = ""
        }) { Text("Join network") }
    }
    selected?.let { row ->
        DisposableEffect(row) { onDispose { password = "" } }
        AlertDialog(onDismissRequest = { selected = null; password = "" }, title = { Text(row.name) }, text = {
            if (row.security == "Open" || row.security == "Enhanced open") Text("Connect to this network?")
            else OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Wi-Fi password") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }, confirmButton = {
            TextButton(onClick = {
                val credential = password
                selected = null; password = ""
                action { connectWifi(wifi, row, credential); "Connecting to ${row.name}…" }
            }) { Text("Connect") }
        }, dismissButton = { TextButton(onClick = { selected = null; password = "" }) { Text("Cancel") } })
    }
}

private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun readWifi(context: Context, wifi: WifiManager): WifiState {
    check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        "Nearby network permission is unavailable"
    }
    val scanning = context.getSystemService(LocationManager::class.java).isLocationEnabled
    val saved = wifi.configuredNetworks.orEmpty().map { WifiRow(it.SSID.removeSurrounding("\""), "Saved", it.networkId) }
    val nearby = if (scanning && wifi.isWifiEnabled) wifi.scanResults.orEmpty()
        .sortedByDescending { it.level }.mapNotNull {
            val name = it.SSID.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val security = when {
                "EAP" in it.capabilities || "WEP" in it.capabilities -> "Administrator setup required"
                "PSK" in it.capabilities -> "WPA2/WPA3"
                "SAE" in it.capabilities -> "WPA3"
                "OWE" in it.capabilities -> "Enhanced open"
                else -> "Open"
            }
            WifiRow(name, security)
        } else emptyList()
    val info = wifi.connectionInfo
    val connected = info?.takeIf { it.networkId >= 0 }?.ssid?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" }
    return WifiState(wifi.isWifiEnabled, scanning, connected,
        (saved + nearby).distinctBy { it.name }.sortedBy { it.name.lowercase() })
}

private fun connectWifi(wifi: WifiManager, row: WifiRow, password: String) {
    require(row.name.toByteArray(Charsets.UTF_8).size in 1..32) { "Network name must be 1–32 bytes" }
    require(row.security != "Administrator setup required") { "This network needs enterprise or legacy setup by your administrator" }
    val config = WifiConfiguration().apply {
        SSID = quoted(row.name)
        hiddenSSID = row.hidden
        when (row.security) {
            "Open" -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN)
            "Enhanced open" -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE)
            "WPA3" -> { require(password.length in 1..63) { "Password must be 1–63 characters" }; setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE); preSharedKey = quoted(password) }
            else -> {
                require(password.length in 8..63 || password.matches(Regex("[0-9a-fA-F]{64}"))) { "Password must be 8–63 characters or a 64-digit hexadecimal key" }
                setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK)
                preSharedKey = if (password.matches(Regex("[0-9a-fA-F]{64}"))) password else quoted(password)
            }
        }
    }
    val id = wifi.addNetwork(config)
    check(id >= 0) { "Android could not save this network" }
    check(wifi.enableNetwork(id, true)) { "Network saved, but Android could not start the connection" }
}
