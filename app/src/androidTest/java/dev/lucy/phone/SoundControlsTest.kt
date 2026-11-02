package dev.lucy.phone

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.lucy.phone.launcher.HomeActivity

/** Runs against Lucy already visible, without a test-harness activity blocked by lock task. */
@RunWith(AndroidJUnit4::class)
class SoundControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun soundModesPreserveAlarmAndMediaVolume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        val original = audio.ringerMode
        val alarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val media = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(20_000) { runCatching {
                compose.onAllNodesWithTag("quickSoundMode").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false) }
            val icon = compose.onNodeWithTag("quickSoundMode").fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Sound icon must be at the top right", icon.center.x > root.width * 0.8f && icon.top < root.height * 0.15f)
            compose.onNodeWithText("Device controls").performClick()
            for ((label, mode) in listOf("Silent" to AudioManager.RINGER_MODE_SILENT,
                "Vibrate" to AudioManager.RINGER_MODE_VIBRATE, "Sound" to AudioManager.RINGER_MODE_NORMAL)) {
                if (audio.ringerMode != mode) compose.onNodeWithTag("sound:$label").performClick()
                compose.waitUntil(5_000) { audio.ringerMode == mode }
                assertEquals(alarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
                assertEquals(media, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
            compose.onNodeWithText("← Home").performClick()
            compose.onNodeWithTag("quickSoundMode").performClick()
            compose.waitUntil(5_000) { audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE }
            compose.onNodeWithTag("quickSoundMode").performClick()
            compose.waitUntil(5_000) { audio.ringerMode == AudioManager.RINGER_MODE_SILENT }
            compose.onNodeWithTag("quickSoundMode").performClick()
            compose.waitUntil(5_000) { audio.ringerMode == AudioManager.RINGER_MODE_NORMAL }
        } finally { audio.ringerMode = original }
    }
}
