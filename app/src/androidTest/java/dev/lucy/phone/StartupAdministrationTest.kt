package dev.lucy.phone

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.lucy.phone.configuration.PolicyRules
import dev.lucy.phone.launcher.HomeActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No bootstrap/cleanup activity: those are correctly blocked on a managed device. */
@RunWith(AndroidJUnit4::class)
class StartupAdministrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun waitFor(text: String) = compose.waitUntil(30_000) {
        runCatching { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }
    private fun homeActivity() = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<HomeActivity>().single()

    @Test fun slowStartupUsesExistingPinAndChromeRemainsSelectable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qa") == "true")
        assertEquals("Lucy_Onboarding_Auto", shell("getprop ro.boot.qemu.avd_name").trim())
        val app = ApplicationProvider.getApplicationContext<LucyApp>()
        app.executor.submit {}.get(60, TimeUnit.SECONDS)
        assertTrue(app.store.configured())
        assertTrue(app.auth.configured())
        val pin = "31840629" // Known disposable fixture only; never replace existing credentials.
        app.auth.unlock(pin)
        app.auth.lock()
        app.adminReturnPage = null
        app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Administration")
        val original = app.engine.draftPolicy().apps.map { it.packageName }.toSet()
        val effective = app.engine.currentPolicy().apps.map { it.packageName }.toSet()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        app.executor.execute {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Test failed to release delayed startup" }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            instrumentation.runOnMainSync { homeActivity().recreate() }
            waitFor("Loading administration…")
            compose.onNodeWithTag("administrationEntry").assertIsNotEnabled()
            compose.onAllNodesWithText("Set up administration").assertCountEquals(0)
            compose.onAllNodesWithText("Set up Lucy preview").assertCountEquals(0)
            compose.onAllNodesWithTag("pin").assertCountEquals(0)
            compose.onNodeWithTag("administrationEntry").performTouchInput { click() }
            compose.onAllNodesWithText("Set up administration").assertCountEquals(0)
        } finally {
            release.countDown()
        }
        waitFor("Administration")
        compose.onNodeWithTag("administrationEntry").assertIsEnabled().performClick()
        waitFor("Administrator access")
        compose.onAllNodesWithTag("confirmPin").assertCountEquals(0)
        compose.onNodeWithTag("pin").performTextInput(pin)
        compose.onNodeWithTag("credentialSubmit").performClick()
        waitFor("1 · Connect & accounts")
        compose.onNodeWithTag("admin:apps").performClick()
        waitFor("Search installed apps")
        val chrome = PolicyRules.CHROME_PACKAGE
        try {
            compose.onNodeWithTag("select:$chrome").performScrollTo().assertIsEnabled().performClick()
            val changed = if (chrome in original) original - chrome else original + chrome
            compose.waitUntil(15_000) { app.engine.draftPolicy().apps.map { it.packageName }.toSet() == changed }
            assertEquals("A checkbox must not mutate the effective policy", effective, app.engine.currentPolicy().apps.map { it.packageName }.toSet())
            assertTrue(app.auth.configured())
            assertNotNull(app.auth.unlock(pin))
        } finally {
            app.engine.saveDraft(app.auth.unlock(pin), original)
            app.auth.lock()
        }
    }
}
