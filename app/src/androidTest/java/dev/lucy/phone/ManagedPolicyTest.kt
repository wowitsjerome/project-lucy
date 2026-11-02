package dev.lucy.phone

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lucy.phone.configuration.Passwords
import dev.lucy.phone.launcher.HomeActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: use ONLY with a newly-created, disposable management emulator. */
@RunWith(AndroidJUnit4::class)
class ManagedPolicyTest {
    @Test fun lifecycleAndCredentialRecovery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("managed") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<LucyApp>()
        assertTrue(app.engine.isOwner)
        assertTrue(app.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        // Wait for application startup reconciliation before writing fixture credentials.
        app.executor.submit {}.get()
        assertFalse("Use a fresh disposable emulator", app.auth.configured())
        val pin = (1..12).map { (0..9).random() }.joinToString("")
        val recovery = app.auth.setup(pin)
        val session = app.auth.unlock(pin)
        val policy = app.engine.currentPolicy()
        assertTrue(policy.apps.isNotEmpty())
        app.engine.saveDraft(session, policy.apps.map { it.packageName }.toSet())
        app.engine.acknowledge(session, "appsReviewed", true)
        app.engine.acknowledge(session, "recoverySaved", true)
        app.engine.acknowledge(session, "navigationTested", true)
        app.engine.prepareFiltering(session)
        val activation = runCatching { app.engine.activate(session, policy.apps.map { it.packageName }.toSet()) }
        if (activation.isFailure) {
            assertFalse("Incomplete first-time setup must not lock down the device", app.store.configured())
            assertFalse(app.engine.filterPassed())
            // Test restoration of an EXISTING managed policy separately from first-time onboarding.
            app.store.update { it.put("activated", true).put("effective", policy.toJson()) }
            app.engine.reconcile()
        }
        assertTrue(app.store.configured())
        assertDeviceControls(app)
        assertThrows(IllegalStateException::class.java) { app.engine.activate("invalid-session", emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { app.engine.activate(session, emptySet()) }
        val revision = app.engine.currentPolicy().revision
        runCatching { app.engine.reconcile() }
        assertEquals(revision, app.engine.currentPolicy().revision)
        assertDeviceControls(app)
        val nextPin = pin.reversed()
        val nextRecovery = app.auth.recover(recovery, nextPin)
        assertNotEquals(recovery, nextRecovery)
        assertFalse(app.auth.validSession(session))
        assertThrows(IllegalStateException::class.java) { app.auth.recover(recovery, pin) }
        val nextSession = app.auth.unlock(nextPin)
        ActivityScenario.launch(HomeActivity::class.java).use {
            app.engine.beginMaintenance(nextSession)
            assertTrue(app.engine.inMaintenance())
            val exitDeadline = SystemClock.elapsedRealtime() + 5_000
            while (app.engine.lockTaskRunning() && SystemClock.elapsedRealtime() < exitDeadline) SystemClock.sleep(100)
            assertFalse("Maintenance must exit the entire lock task session", app.engine.lockTaskRunning())
            assertTrue(app.engine.dpm.getLockTaskPackages(app.engine.admin).isEmpty())
            assertFalse(app.engine.dpm.getUserRestrictions(app.engine.admin).getBoolean(android.os.UserManager.DISALLOW_INSTALL_APPS))
            app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(1_000)
            // An expired persisted lease follows the same recovery path as a process/boot interruption.
            app.store.update { state -> state.put("maintenance", JSONObject().put("deadline", 0).put("boot", app.engine.bootCount())) }
            runCatching { app.engine.reconcile(restoreMaintenance = true) }
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!app.engine.lockTaskRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("Expiry must return to HOME and re-enter lock task, including from Settings", app.engine.lockTaskRunning())
        }
        assertFalse(app.engine.inMaintenance())
        assertDeviceControls(app)
        app.auth.requireSession(nextSession)
    }
    private fun assertDeviceControls(app: LucyApp) {
        assertEquals(emptyList<String>(), app.engine.verify(app.engine.currentPolicy()).filterNot { it == "Private DNS policy mismatch" })
        assertFalse(app.engine.dpm.isApplicationHidden(app.engine.admin, "com.google.android.gms"))
    }
    @Test fun livePrivateDnsRequiresReachableProvider() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("managed") == "true")
        val app = ApplicationProvider.getApplicationContext<LucyApp>()
        assumeTrue("Live DNS-over-TLS must be available on the host network",
            app.engine.isOwner && app.engine.dpm.getGlobalPrivateDnsHost(app.engine.admin) == "family.cloudflare-dns.com")
        app.engine.checkFilter()
        assumeTrue("Allowed-domain control cannot resolve on this network; blocking cannot be assessed",
            app.store.read().getJSONObject("filter").getBoolean("controlPassed"))
        assertTrue(app.store.read().getJSONObject("filter").getBoolean("passed"))
    }
}
