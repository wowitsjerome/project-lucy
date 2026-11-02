package dev.lucy.phone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("L U C Y").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Administration").assertIsDisplayed()
    }
    @Test fun homeAndControlsAreUsable() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("L U C Y").assertIsDisplayed()
        compose.onNodeWithText("Device controls").performClick()
        compose.onNodeWithText("Launcher brightness").assertIsDisplayed()
        compose.onNodeWithText("Volume +").assertIsDisplayed()
        compose.onNodeWithText("← Home").performClick()
        compose.onNodeWithText("Administration").performClick()
        compose.onNodeWithTag("pin").assertIsDisplayed()
    }
}
