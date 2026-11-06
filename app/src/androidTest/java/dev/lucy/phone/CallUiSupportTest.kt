package dev.lucy.phone

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallUiSupportTest {
    @Test fun systemInCallUiIsDiscoveredAndRequired() {
        val app = ApplicationProvider.getApplicationContext<LucyApp>()
        val packages = app.engine.inventory.inCallUiPackages()
        assumeTrue("Run the OEM assertion on Samsung hardware", Build.MANUFACTURER.equals("samsung", ignoreCase = true))
        assertTrue("Samsung's separate in-call interface must be discovered", "com.samsung.android.incallui" in packages)
        val policy = app.engine.inventory.withRequiredSystemSupport(app.engine.currentPolicy())
        assertTrue("In-call UI must be available to lock-task calls", "com.samsung.android.incallui" in policy.activitySupport)
        assertTrue("In-call UI must remain unhidden", "com.samsung.android.incallui" in policy.support)
    }
}
