package dev.lucy.phone

import android.app.ActivityOptions
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lucy.phone.configuration.PolicyRules
import dev.lucy.phone.launcher.HomeActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reuses only the known, already-managed disposable onboarding fixture; never replaces credentials. */
@RunWith(AndroidJUnit4::class)
class ChromePolicyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun await(message: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, check())
    }

    @Test fun chromeApprovalLaunchAndRevocation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qa") == "true")
        assertEquals("Lucy_Onboarding_Auto", shell("getprop ro.boot.qemu.avd_name").trim())
        val app = ApplicationProvider.getApplicationContext<LucyApp>()
        app.executor.submit {}.get()
        assertTrue(app.engine.isOwner)
        assertTrue("Run after the onboarding fixture", app.store.configured())
        val pin = "31840629"
        app.auth.unlock(pin) // Fail before any mutations if someone has replaced fixture credentials.
        val original = app.engine.currentPolicy().apps.map { it.packageName }.toSet()
        val chrome = PolicyRules.CHROME_PACKAGE
        val entry = app.engine.inventory.apps(app.engine.currentPolicy()).single { it.packageName == chrome }
        assertFalse(chrome in app.engine.inventory.forbidden())
        assertTrue("Chrome must remain optional", !entry.essential)
        fun apply(selected: Set<String>) {
            val token = app.auth.unlock(pin)
            app.engine.saveDraft(token, selected)
            app.engine.acknowledge(token, "appsReviewed", true)
            app.engine.applyDraft(token)
        }
        try {
            apply(original - chrome)
            assertTrue(app.engine.dpm.isApplicationHidden(app.engine.admin, chrome))
            assertFalse(app.engine.dpm.isLockTaskPermitted(chrome))
            val token = app.auth.unlock(pin)
            app.engine.saveDraft(token, original + chrome)
            assertFalse("Draft alone must not approve Chrome", chrome in app.engine.currentPolicy().lockPackages(app.packageName))
            app.engine.acknowledge(token, "appsReviewed", true)
            app.engine.applyDraft(token)
            assertFalse(app.engine.dpm.isApplicationHidden(app.engine.admin, chrome))
            assertTrue(app.engine.dpm.isLockTaskPermitted(chrome))
            assertTrue(app.engine.dpm.isApplicationHidden(app.engine.admin, "com.android.vending"))
            assertFalse(app.engine.dpm.isLockTaskPermitted("com.android.settings"))
            assertTrue(app.engine.dpm.getUserRestrictions(app.engine.admin).getBoolean(UserManager.DISALLOW_INSTALL_APPS))
            assertTrue(app.engine.verify(app.engine.currentPolicy()).isEmpty())
            app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Home must enter managed lock task") { app.engine.lockTaskRunning() }
            app.startActivity(app.engine.inventory.launchIntent(entry), ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
            await("Approved Chrome must actually open") {
                shell("dumpsys activity activities").lineSequence().any { it.contains("topResumedActivity") && it.contains("com.android.chrome/") }
            }
            shell("input keyevent KEYCODE_HOME")
            await("Home must return to Lucy") {
                shell("dumpsys activity activities").lineSequence().any { it.contains("topResumedActivity") && it.contains("dev.lucy.phone/.launcher.HomeActivity") }
            }
            apply(original - chrome)
            assertTrue(app.engine.dpm.isApplicationHidden(app.engine.admin, chrome))
            assertFalse(app.engine.dpm.isLockTaskPermitted(chrome))
            assertTrue(app.engine.inventory.exists(chrome))
            assertTrue(app.engine.verify(app.engine.currentPolicy()).isEmpty())
        } finally {
            apply(original)
        }
    }
}
