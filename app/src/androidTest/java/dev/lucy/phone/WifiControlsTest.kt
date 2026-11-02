@file:Suppress("DEPRECATION")
package dev.lucy.phone

import android.content.Intent
import android.net.wifi.WifiManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lucy.phone.launcher.HomeActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun dailyWifiControlsKeepPolicyEnforced() {
        val app = ApplicationProvider.getApplicationContext<LucyApp>()
        app.executor.submit {}.get()
        assertTrue(app.engine.isOwner)
        assertTrue(app.store.configured())
        assertFalse("Run after returning to daily mode", app.engine.inMaintenance())
        val packages = app.engine.dpm.getLockTaskPackages(app.engine.admin).toSet()
        val wifi = app.getSystemService(WifiManager::class.java)
        val enabled = wifi.isWifiEnabled
        app.startActivity(Intent(app, HomeActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(20_000) { runCatching { compose.onAllNodesWithText("Device controls").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithText("Device controls").performClick()
        compose.onNodeWithTag("wifiControls").performScrollTo().performClick()
        compose.onNodeWithTag("wifiStatus").assertExists()
        try {
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithText(if (enabled) "Turn Wi-Fi off" else "Turn Wi-Fi on").assertIsEnabled(); true }.getOrDefault(false) }
            compose.onNodeWithText(if (enabled) "Turn Wi-Fi off" else "Turn Wi-Fi on").performClick()
            compose.waitUntil(15_000) { wifi.isWifiEnabled != enabled }
        } finally {
            if (wifi.isWifiEnabled != enabled) assertTrue(wifi.setWifiEnabled(enabled))
        }
        compose.waitUntil(20_000) { wifi.isWifiEnabled == enabled }
        assertTrue("Wi-Fi access must keep lock task active", app.engine.lockTaskRunning())
        assertEquals(packages, app.engine.dpm.getLockTaskPackages(app.engine.admin).toSet())
        assertFalse(app.engine.inMaintenance())
        assertTrue(app.engine.dpm.getUserRestrictions(app.engine.admin).getBoolean(android.os.UserManager.DISALLOW_INSTALL_APPS))
        assertFalse("Settings must remain outside daily allowlist", packages.contains("com.android.settings"))
        compose.onNodeWithText("← Home").performScrollTo().performClick()
    }
}
