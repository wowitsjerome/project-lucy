package dev.lucy.phone

import dev.lucy.phone.launcher.AdminSessionBoundary
import dev.lucy.phone.launcher.AdminActivityHandoffStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminSessionBoundaryTest {
    @Test fun ordinaryAdminPauseReturnsToHomeWithoutPreservingTheAdminScreen() {
        assertFalse(AdminSessionBoundary().shouldPreserveAdminOnPause(pausingAdministration = true))
    }

    @Test fun explicitExternalToolPreservesOnlyOnePause() {
        val boundary = AdminSessionBoundary()
        boundary.expectExternalPause()
        assertTrue(boundary.shouldPreserveAdminOnPause(pausingAdministration = true))
        assertFalse(boundary.shouldPreserveAdminOnPause(pausingAdministration = true))
    }

    @Test fun failedLaunchCannotPreserveALaterPause() {
        val boundary = AdminSessionBoundary()
        boundary.expectExternalPause()
        boundary.cancelExternalPause()
        assertFalse(boundary.shouldPreserveAdminOnPause(pausingAdministration = true))
    }

    @Test fun grantIsConsumedOutsideAdministration() {
        val boundary = AdminSessionBoundary()
        boundary.expectExternalPause()
        assertFalse(boundary.shouldPreserveAdminOnPause(pausingAdministration = false))
        assertFalse(boundary.shouldPreserveAdminOnPause(pausingAdministration = true))
    }

    @Test fun activityHandoffIsShortLivedAndSingleUse() {
        var now = 1_000L
        val handoff = AdminActivityHandoffStore { now }
        handoff.prepare("token", "connect")
        assertEquals("token", handoff.consume()?.session)
        assertNull(handoff.consume())

        handoff.prepare("later", "apps")
        now += 15_000
        assertNull(handoff.consume())
    }
}
