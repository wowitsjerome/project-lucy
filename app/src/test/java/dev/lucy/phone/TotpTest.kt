package dev.lucy.phone

import dev.lucy.phone.configuration.Totp
import org.junit.Assert.*
import org.junit.Test

class TotpTest {
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test fun followsRfc6238Sha1VectorAtSixDigits() {
        assertEquals("287082", Totp.code(rfcSecret, 59 / 30))
        assertEquals("081804", Totp.code(rfcSecret, 1_111_111_109L / 30))
    }

    @Test fun base32AndProvisioningUriAreAuthenticatorCompatible() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(rfcSecret))
        assertEquals("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ", Totp.formattedSetupKey(rfcSecret))
        assertEquals("otpauth://totp/Lucy%3AAdministrator?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Lucy&algorithm=SHA1&digits=6&period=30",
            Totp.provisioningUri(rfcSecret))
    }

    @Test fun acceptsNarrowClockSkewAndRejectsReuseOrMalformedCodes() {
        val now = 1_700_000_000_000L
        val current = now / 1000 / Totp.PERIOD_SECONDS
        assertEquals(current - 1, Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, current - 1), now))
        assertEquals(current, Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, current), now))
        assertEquals(current + 1, Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, current + 1), now))
        assertNull(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, current), now, lastAcceptedStep = current))
        assertNull(Totp.matchingStep(rfcSecret, "12345", now))
        assertNull(Totp.matchingStep(rfcSecret, "１２３４５６", now))
    }
}
