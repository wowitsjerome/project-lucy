package dev.lucy.phone

import dev.lucy.phone.configuration.PolicyTransaction
import dev.lucy.phone.configuration.keepActivated
import org.junit.Assert.*
import org.junit.Test

class PolicyTransactionTest {
    @Test fun successfulApplicationDoesNotRollBack() {
        val applied = mutableListOf<Int>()
        val result = PolicyTransaction.run(2, 1, { applied += it }, { emptyList() })
        assertTrue(result.applied); assertEquals(listOf(2), applied)
    }
    @Test fun verificationFailureRestoresPreviousPolicy() {
        val applied = mutableListOf<Int>()
        val result = PolicyTransaction.run(2, 1, { applied += it }, { if (it == 2) listOf("Missing restriction") else emptyList() })
        assertFalse(result.applied); assertTrue(result.restored); assertEquals(listOf(2, 1), applied)
    }
    @Test fun partiallyAppliedPolicyAlsoRollsBack() {
        var active = 1
        val result = PolicyTransaction.run(2, 1, { active = it; if (it == 2) error("DNS unavailable") }, { emptyList() })
        assertEquals(1, active); assertTrue(result.restored); assertEquals("DNS unavailable", result.error)
    }
    @Test fun failedRollbackNeverReportsSuccess() {
        val result = PolicyTransaction.run(2, 1, { _: Int -> error("Platform unavailable") }, { emptyList() })
        assertFalse(result.applied); assertFalse(result.restored)
    }
    @Test fun firstActivationFailureHasNoImaginaryFallback() {
        val result = PolicyTransaction.run(2, null, { _: Int -> error("Rejected") }, { emptyList() })
        assertFalse(result.applied); assertFalse(result.restored)
        assertFalse(result.keepActivated(hadPreviousPolicy = false))
    }
    @Test fun failedUpdateDoesNotTurnManagedDeviceBackIntoOnboarding() {
        val result = PolicyTransaction.run(2, 1, { _: Int -> error("Rejected") }, { emptyList() })
        assertTrue(result.keepActivated(hadPreviousPolicy = true))
    }
}
