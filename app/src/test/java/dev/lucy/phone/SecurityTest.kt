package dev.lucy.phone

import dev.lucy.phone.configuration.*
import org.junit.Assert.*
import org.junit.Test

class SecurityTest {
    @Test fun administratorSessionUsesThirtySecondInactivityWindow() {
        assertEquals(30_000L, ADMIN_SESSION_TIMEOUT_MILLIS)
        assertTrue(adminSessionActive(29_999, 0))
        assertFalse(adminSessionActive(30_000, 0))
    }
    @Test fun fifthFailureStartsThrottleAndBackoffCaps() {
        var state = AttemptState(boot = 1)
        repeat(4) { state = state.failed(1000, 1); assertEquals(0, state.remaining(1000)) }
        state = state.failed(1000, 1); assertEquals(30_000, state.remaining(1000))
        state = state.failed(31_000, 1); assertEquals(60_000, state.remaining(31_000))
        repeat(20) { state = state.failed(1000, 1) }
        assertEquals(900_000, state.remaining(1000))
    }
    @Test fun rebootCannotErasePenalty() {
        val state = AttemptState(5, 60_000, 1).rebase(100, 2)
        assertEquals(30_000, state.remaining(100))
        assertEquals(state, state.rebase(500, 2))
    }
    @Test fun maintenanceCannotSurviveExpiryOrNewBoot() {
        val window = MaintenanceWindow(60_000, 4)
        assertTrue(window.active(59_999, 4))
        assertFalse(window.active(60_000, 4))
        assertFalse(window.active(10, 5))
    }
    @Test fun verifierRejectsWrongSecretAndUsesIndependentSalts() {
        val salt = Passwords.random()
        val hash = Passwords.hash("12345678", salt)
        assertTrue(Passwords.verify("12345678", salt, hash))
        assertFalse(Passwords.verify("12345679", salt, hash))
        assertNotEquals(hash, Passwords.hash("12345678", Passwords.random()))
        assertTrue(Passwords.random(20).length >= 26)
    }
    @Test fun pinMustUseAtLeastEightAsciiDigits() {
        assertFalse(Passwords.validPin("1234")); assertFalse(Passwords.validPin("abcdefgh"))
        assertFalse(Passwords.validPin("１２３４５６７８")); assertTrue(Passwords.validPin("01234567"))
    }
}
