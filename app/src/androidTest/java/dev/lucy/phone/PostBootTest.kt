package dev.lucy.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.KeyguardManager
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostBootTest {
    @Test fun deviceControlsSurviveReboot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("managed") == "true")
        val app = ApplicationProvider.getApplicationContext<LucyApp>()
        app.executor.submit {}.get()
        assertTrue(app.engine.isOwner)
        assertTrue(app.store.configured())
        assertFalse("The emulator must be unlocked for the post-boot check", app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertFalse(app.engine.inMaintenance())
        assertEquals(emptyList<String>(), app.engine.verify(app.engine.currentPolicy()).filterNot { it == "Private DNS policy mismatch" })
        assertFalse(app.engine.dpm.isApplicationHidden(app.engine.admin, "com.google.android.gms"))
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!app.engine.lockTaskRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Managed HOME must restart lock task after unlock", app.engine.lockTaskRunning())
    }
}
