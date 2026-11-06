package dev.lucy.phone

import dev.lucy.phone.configuration.*
import org.junit.Assert.*
import org.junit.Test

class PolicyRulesTest {
    private val policy = DevicePolicy(baseline = "fixture", apps = listOf(AppEntry("phone", "Phone", "Phone", true)),
        support = mapOf("provider" to "Contacts database"), activitySupport = setOf("permissions"), blocked = setOf("browser"))
    private fun validate(candidate: DevicePolicy) = PolicyRules.validate(candidate, setOf("phone"), "lucy", setOf("browser", "settings"))
    @Test fun acceptsSafePolicy() { assertTrue(validate(policy).isEmpty()) }
    @Test fun rejectsEssentialRemoval() { assertTrue(validate(policy.copy(apps = listOf(AppEntry("clock", "Clock", "Clock")))).isNotEmpty()) }
    @Test fun blocksBrowserEvenAsSupportActivity() { assertTrue(validate(policy.copy(activitySupport = setOf("browser"))).isNotEmpty()) }
    @Test fun preventsHidingDependencies() { assertTrue(validate(policy.copy(blocked = setOf("provider"))).isNotEmpty()) }
    @Test fun doesNotAllowDnsDisable() { assertTrue(validate(policy.copy(dnsHost = "unfiltered.test")).isNotEmpty()) }
    @Test fun rejectsDuplicates() { assertTrue(validate(policy.copy(apps = policy.apps + policy.apps)).isNotEmpty()) }
    @Test fun jsonRoundTripPreservesPolicy() { assertEquals(policy, DevicePolicy.fromJson(policy.toJson())) }
    @Test fun futureSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { DevicePolicy.fromJson(policy.toJson().put("schemaVersion", 2)) }
    }
    @Test fun supportDoesNotAutomaticallyGainActivityAccess() { assertFalse("provider" in policy.lockPackages("lucy")) }
    @Test fun requiredSystemActivitySupportIsAllowedAndUnblocked() {
        val upgraded = policy.copy(blocked = policy.blocked + "incall").requiringActivitySupport(setOf("incall"), "System in-call interface")
        assertEquals("System in-call interface", upgraded.support["incall"])
        assertTrue("incall" in upgraded.activitySupport)
        assertTrue("incall" in upgraded.lockPackages("lucy"))
        assertFalse("incall" in upgraded.blocked)
        assertTrue(validate(upgraded).isEmpty())
    }
    @Test fun missingOptionalAppsArePrunedButEssentialAppsRemain() {
        val optional = AppEntry("calendar", "Calendar", "Calendar")
        val withOptional = policy.copy(apps = policy.apps + optional)
        assertEquals(policy.apps, withOptional.pruningMissingOptionalApps(setOf("phone")).apps)
        assertEquals(policy.apps, withOptional.pruningMissingOptionalApps(emptySet()).apps)
        assertEquals(listOf(optional), policy.copy(apps = listOf(optional)).pruningMissingOptionalApps(emptySet()).apps)
    }
    @Test fun chromeIsSelectableEvenWhenDiscoveredAsBrowser() {
        val forbidden = PolicyRules.forbiddenPackages(setOf(PolicyRules.CHROME_PACKAGE, "browser"))
        val candidate = policy.copy(apps = policy.apps + AppEntry(PolicyRules.CHROME_PACKAGE, "Chrome", "Chrome"))
        assertFalse(PolicyRules.CHROME_PACKAGE in forbidden)
        assertTrue(PolicyRules.validate(candidate, setOf("phone"), "lucy", forbidden).isEmpty())
        assertTrue(PolicyRules.CHROME_PACKAGE in candidate.lockPackages("lucy"))
    }
    @Test fun chromeEligibilityDoesNotAutomaticallyApproveIt() {
        assertFalse(PolicyRules.CHROME_PACKAGE in policy.lockPackages("lucy"))
        assertTrue(validate(policy.copy(blocked = policy.blocked + PolicyRules.CHROME_PACKAGE)).isEmpty())
    }
    @Test fun chromeExceptionDoesNotPermitOtherBrowsersStoresOrSettings() {
        val restricted = setOf("browser", "com.chrome.beta", "com.android.settings", "com.android.vending",
            "com.sec.android.app.samsungapps", "com.sec.android.app.sbrowser")
        val forbidden = PolicyRules.forbiddenPackages(setOf("browser", "com.chrome.beta", PolicyRules.CHROME_PACKAGE))
        assertEquals(restricted, forbidden)
        restricted.forEach { pkg ->
            val candidate = policy.copy(activitySupport = setOf(pkg), blocked = emptySet())
            assertTrue(PolicyRules.validate(candidate, setOf("phone"), "lucy", forbidden).isNotEmpty())
        }
    }
}
