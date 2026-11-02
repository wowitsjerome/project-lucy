package dev.lucy.phone

import dev.lucy.phone.configuration.Onboarding
import org.junit.Assert.*
import org.junit.Test

class OnboardingTest {
    @Test fun completedChecklistCanProceed() {
        assertTrue(Onboarding.blockers(true, true, true, true, true, true, true).isEmpty())
    }
    @Test fun offlineFilteringBlocksActivationEvenWhenEverythingElseIsReady() {
        assertEquals(listOf("Run a passing filtering check on this network"),
            Onboarding.blockers(true, true, true, true, true, true, false))
    }
    @Test fun checklistExplainsEveryMissingPrerequisite() {
        assertEquals(7, Onboarding.blockers(false, false, false, false, false, false, false).size)
    }
    @Test fun previewIsNotMistakenForManagedSetup() {
        assertEquals(1, Onboarding.blockers(false, true, true, true, true, true, true).size)
    }
    @Test fun unreviewedAppsBlockHandoff() {
        assertTrue(Onboarding.blockers(true, true, true, false, true, true, true).single().contains("selected apps"))
    }
}
