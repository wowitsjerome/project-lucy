package dev.lucy.phone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import dev.lucy.phone.launcher.AppearanceMode
import dev.lucy.phone.launcher.HomeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherTest {
    @get:Rule val compose = createAndroidComposeRule<HomeActivity>()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    @Test fun launchesPhoneAndHomeReturnsToLucy() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Phone").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Phone").performClick()
        compose.waitUntil(10_000) {
            shell("dumpsys activity activities").lineSequence().any {
                it.contains("topResumedActivity") && it.contains("com.google.android.dialer/")
            }
        }
        shell("input keyevent KEYCODE_HOME")
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("Administration").assertIsDisplayed(); true }.getOrDefault(false)
        }
    }
    @Test fun homeAndControlsAreUsable() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("L U C Y").assertIsDisplayed()
        compose.onNodeWithText("Phone controls").performClick()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithTag("appearance:SYSTEM").assertExists()
        compose.onNodeWithTag("appearance:LIGHT").assertExists()
        compose.onNodeWithTag("appearance:DARK").assertExists()
        compose.onNodeWithText("Launcher brightness").assertIsDisplayed()
        compose.onNodeWithText("Volume +").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose Home screen apps").performScrollTo().performClick()
        compose.onNodeWithText("Home screen apps").assertIsDisplayed()
        compose.onNodeWithText("Choose which administrator-approved apps appear on Lucy Home. Hiding an app does not uninstall it or revoke its approved access.").assertIsDisplayed()
        compose.onNodeWithText("← Phone controls").performClick()
        compose.onNodeWithText("← Home").performClick()
        compose.onNodeWithText("Administration").performClick()
        compose.onNodeWithTag("pin").assertIsDisplayed()
    }
    @Test fun appearancePersistsAcrossActivityRecreation() {
        val app = compose.activity.application as LucyApp
        val original = app.appearance.get()
        val changed = if (original == AppearanceMode.DARK) AppearanceMode.LIGHT else AppearanceMode.DARK
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Phone controls").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Phone controls").performClick()
            compose.onNodeWithTag("appearance:${changed.name}").performClick().assertIsSelected()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Phone controls").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Phone controls").performClick()
            compose.onNodeWithTag("appearance:${changed.name}").assertIsSelected()
        } finally {
            app.appearance.set(original)
        }
    }
}
