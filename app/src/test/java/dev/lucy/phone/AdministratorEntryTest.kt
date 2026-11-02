package dev.lucy.phone

import dev.lucy.phone.configuration.AdministratorEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AdministratorEntryTest {
    @Test fun unknownStateNeverOffersPinCreation() {
        assertEquals(AdministratorEntry.LOADING, AdministratorEntry.resolve(null, false))
        assertEquals(AdministratorEntry.LOADING, AdministratorEntry.resolve(null, true))
    }
    @Test fun configuredAdministratorAlwaysUsesExistingPin() {
        assertEquals(AdministratorEntry.LOGIN, AdministratorEntry.resolve(true, false))
        assertEquals(AdministratorEntry.LOGIN, AdministratorEntry.resolve(true, true))
    }
    @Test fun confirmedNewUnmanagedSetupCanCreatePin() {
        assertEquals(AdministratorEntry.SETUP, AdministratorEntry.resolve(false, false))
    }
    @Test fun managedDeviceWithMissingCredentialsCannotCreateReplacement() {
        assertEquals(AdministratorEntry.UNAVAILABLE, AdministratorEntry.resolve(false, true))
    }
    @Test fun readFailureNeverOffersPinCreationOrAuthentication() {
        listOf(null, false, true).forEach { configured ->
            assertEquals(AdministratorEntry.UNAVAILABLE, AdministratorEntry.resolve(configured, false, true))
        }
    }
}
