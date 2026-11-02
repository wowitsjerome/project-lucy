package dev.lucy.phone

import android.app.admin.DevicePolicyManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.lucy.phone.configuration.DevicePolicy
import dev.lucy.phone.configuration.PolicyRules
import dev.lucy.phone.launcher.HomeActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Known credentials and fixture policy are restricted to our new disposable QA AVD, never a user's managed AVD. */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<HomeActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun waitFor(text: String) = compose.waitUntil(30_000) {
        runCatching { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun login(pin: String) {
        waitFor("Administrator access")
        compose.onNodeWithTag("pin").performTextInput(pin)
        compose.onNodeWithTag("credentialSubmit").performClick()
    }
    private fun screenshot(name: String) {
        // Only this fixture's nonsensitive admin pages are captured. Production FLAG_SECURE remains unchanged.
        fun setSecure(secure: Boolean) = instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<HomeActivity>().single()
            if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setSecure(false)
        compose.waitForIdle()
        shell("screencap -p /sdcard/$name.png")
        setSecure(true)
    }
    @Test fun guidedSetupAndOfflineSafety() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qa") == "true")
        assertEquals("Lucy_Onboarding_Auto", shell("getprop ro.boot.qemu.avd_name").trim())
        val app = compose.activity.application as LucyApp
        app.executor.submit {}.get()
        assertTrue(app.engine.isOwner)
        val pin = "31840629" // Disposable test credential, not an application default.
        if (!app.auth.configured()) app.auth.setup(pin)
        // Retry an interrupted run only with its known fixture PIN; never replace unknown credentials.
        var token = app.auth.unlock(pin)
        val initial = app.engine.currentPolicy()
        app.engine.saveDraft(token, initial.apps.map { it.packageName }.toSet())
        assertThrows(IllegalStateException::class.java) { app.engine.applyDraft(token) }
        assertFalse("Incomplete setup must not activate restrictions", app.store.configured())
        assertFalse(app.engine.lockTaskRunning())
        app.changed()
        compose.runOnUiThread { compose.activity.recreate() }
        waitFor("Continue phone setup")
        compose.onNodeWithText("Administration").performClick()
        login(pin)
        waitFor("1 · Connect & accounts")
        screenshot("qa-administration")
        compose.onNodeWithTag("admin:connect").performClick()
        waitFor("Wi-Fi settings")
        compose.onNodeWithText("Open system Settings").assertIsEnabled()
        compose.onNodeWithText("Open Chrome / browser").assertIsEnabled()
        screenshot("qa-connect")
        click("Open system Settings")
        compose.waitUntil(15_000) { shell("dumpsys activity activities").lineSequence().any { it.contains("topResumedActivity") && it.contains("com.android.settings/") } }
        instrumentation.uiAutomation.waitForIdle(500, 10_000)
        shell("input keyevent KEYCODE_HOME")
        login(pin)
        waitFor("Wi-Fi settings") // Returning from Settings resumes the same task after reauthentication.
        compose.onNodeWithText("← Administration").performClick()
        compose.onNodeWithTag("admin:apps").performClick()
        waitFor("Search installed apps")
        screenshot("qa-apps")
        compose.onNodeWithTag("select:${PolicyRules.CHROME_PACKAGE}").assertIsEnabled().assertIsOff()
        assertTrue("Settings remains setup-only", compose.onAllNodesWithTag("select:com.android.settings").fetchSemanticsNodes().isEmpty())
        assertTrue("Play remains setup-only", compose.onAllNodesWithTag("select:com.android.vending").fetchSemanticsNodes().isEmpty())
        val optional = app.engine.inventory.apps(initial).first { it.packageName == PolicyRules.CHROME_PACKAGE }
        compose.onNodeWithTag("select:${optional.packageName}").performScrollTo().performClick()
        compose.waitUntil(15_000) { app.engine.draftPolicy().apps.any { it.packageName == optional.packageName } }
        assertFalse("Draft changes must not mutate effective policy", app.engine.currentPolicy().apps.any { it.packageName == optional.packageName })
        token = app.auth.unlock(pin)
        assertThrows(IllegalStateException::class.java) { app.engine.beginTemporaryInternet("invalid", true) }
        assertThrows(IllegalStateException::class.java) { app.engine.beginTemporaryInternet(token, false) }
        app.engine.beginTemporaryInternet(token, true)
        assertTrue(app.engine.temporaryInternetActive())
        assertFalse(app.engine.filterPassed())
        assertEquals(DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC, app.engine.dpm.getGlobalPrivateDnsMode(app.engine.admin))
        app.store.update { it.getJSONObject("temporaryInternet").put("deadline", 0) }
        app.engine.expireSessions()
        assertFalse(app.engine.temporaryInternetActive())
        assertEquals(DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, app.engine.dpm.getGlobalPrivateDnsMode(app.engine.admin))
        assertEquals(DevicePolicy.DNS_HOST, app.engine.dpm.getGlobalPrivateDnsHost(app.engine.admin))
        assertFalse(app.store.read().has("temporaryInternet"))
        app.engine.beginTemporaryInternet(token, true)
        app.engine.reconcile(restoreMaintenance = true)
        assertFalse("Process/boot recovery must end the exception even before activation", app.engine.temporaryInternetActive())
        assertEquals(DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, app.engine.dpm.getGlobalPrivateDnsMode(app.engine.admin))
        app.engine.prepareFiltering(token)
        val result = app.store.read().getJSONObject("filter")
        assertEquals(3, result.getJSONArray("checks").length())
        if (!result.getBoolean("controlPassed")) assertFalse(result.getBoolean("passed"))
        app.engine.acknowledge(token, "appsReviewed", true)
        app.engine.acknowledge(token, "recoverySaved", true)
        app.engine.acknowledge(token, "navigationTested", true)
        if (!app.engine.filterPassed()) {
            assertThrows(IllegalStateException::class.java) { app.engine.applyDraft(token) }
            assertFalse("Unverified filtering must leave first-time setup unlocked", app.store.configured())
        }
        // Offline host cannot accept first-time setup. Simulate an existing managed device to test upgrade/maintenance restoration independently.
        app.store.update { it.put("activated", true).put("effective", initial.toJson()) }
        app.engine.reconcile()
        assertTrue(app.engine.verify(app.engine.currentPolicy()).isEmpty())
        token = app.auth.unlock(pin)
        app.engine.beginMaintenance(token)
        app.engine.beginTemporaryInternet(token, true)
        val draft = app.engine.draftPolicy().apps.map { it.packageName }.toSet()
        app.engine.applyDraft(token)
        assertEquals(draft, app.engine.currentPolicy().apps.map { it.packageName }.toSet())
        assertFalse(app.engine.inMaintenance())
        assertFalse(app.engine.temporaryInternetActive())
        assertTrue(app.engine.verify(app.engine.currentPolicy()).isEmpty())
        assertFalse(app.engine.dpm.isApplicationHidden(app.engine.admin, PolicyRules.CHROME_PACKAGE))
        assertTrue(app.engine.dpm.isLockTaskPermitted(PolicyRules.CHROME_PACKAGE))
        assertTrue(app.engine.dpm.isApplicationHidden(app.engine.admin, "com.android.vending"))
        assertTrue(app.auth.configured())
        assertNotNull(app.auth.unlock(pin))
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!app.engine.lockTaskRunning() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(app.engine.lockTaskRunning())
    }
}
