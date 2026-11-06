package dev.lucy.phone

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.lucy.phone.configuration.Totp
import dev.lucy.phone.launcher.HomeActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in: mutates credentials only on the isolated, disposable Lucy_Launcher emulator. */
class AuthenticatorFlowTest {
    @get:Rule val compose = createAndroidComposeRule<HomeActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun enrollsUsesAndRecoversAuthenticatorFirstLogin() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("authenticator") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        assertEquals("Lucy_Launcher", shell("getprop ro.boot.qemu.avd_name").trim())
        val app = compose.activity.application as LucyApp
        app.executor.submit {}.get()
        assertFalse("Use a fresh disposable launcher emulator", app.auth.configured())
        val pin = "31840629"
        val recovery = app.auth.setup(pin)
        val session = app.auth.unlock(pin)
        val enrollment = app.auth.beginTotpEnrollment(session)
        val encodedSecret = android.net.Uri.parse(enrollment.uri).getQueryParameter("secret")!!
        assertEquals(encodedSecret, enrollment.setupKey.replace(" ", ""))
        val secret = decodeBase32(encodedSecret)
        val currentStep = System.currentTimeMillis() / 1000 / Totp.PERIOD_SECONDS
        app.auth.confirmTotpEnrollment(session, Totp.code(secret, currentStep))
        assertTrue(app.auth.totpConfigured())
        assertFalse(java.io.File(app.filesDir, "admin.json").readText().contains(encodedSecret))
        app.auth.lock()

        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Administration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Administration").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("totp").fetchSemanticsNodes().isNotEmpty() }
        val loginCode = Totp.code(secret, currentStep + 1)
        compose.onNodeWithTag("totp").performTextInput(loginCode)
        compose.onNodeWithTag("credentialSubmit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Security & recovery").fetchSemanticsNodes().isNotEmpty() }
        assertThrows(IllegalStateException::class.java) { app.auth.unlockTotp(loginCode) }

        compose.onNodeWithText("Security & recovery").performClick()
        compose.onNodeWithText("Enabled. Administration opens with a current six-digit authenticator code. Each code can be accepted only once; the backup PIN remains available from the login screen.").assertIsDisplayed()
        compose.onNodeWithText("Replace authenticator").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("totpEnrollmentQr").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("totpEnrollmentQr").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Can’t scan? Show setup key").performScrollTo().performClick()
        compose.onNodeWithTag("totpSetupKey").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("← Administration").performClick()
        compose.onNodeWithText("Lock administration").performClick()
        compose.onNodeWithText("Administration").performClick()
        compose.onNodeWithTag("totp").assertIsDisplayed()
        compose.onNodeWithText("Use backup PIN").performClick()
        compose.onNodeWithTag("pin").assertIsDisplayed()

        app.auth.recover(recovery, pin.reversed())
        assertFalse("Recovery must remove the enrolled authenticator", app.auth.totpConfigured())
    }

    private fun decodeBase32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val decoded = ArrayList<Byte>()
        value.forEach { character ->
            buffer = (buffer shl 5) or alphabet.indexOf(character)
            bits += 5
            if (bits >= 8) {
                decoded += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }
        return decoded.toByteArray()
    }
}
