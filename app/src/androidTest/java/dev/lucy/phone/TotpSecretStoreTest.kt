package dev.lucy.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lucy.phone.configuration.TotpSecretStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class TotpSecretStoreTest {
    @Test fun encryptsReadsAndClearsSecretWithAndroidKeystore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fileName = "test-totp-${System.nanoTime()}.json"
        val store = TotpSecretStore(context, "lucy-test-totp-${System.nanoTime()}", fileName)
        val secret = "not-a-real-authenticator-secret".toByteArray()
        try {
            assertFalse(store.exists())
            store.write(secret)
            assertTrue(store.exists())
            assertArrayEquals(secret, store.read())
            val persisted = File(context.filesDir, fileName).readText()
            assertFalse(persisted.contains(secret.decodeToString()))
            assertFalse(persisted.contains(Base64.getEncoder().encodeToString(secret)))
        } finally {
            store.clear()
        }
        assertFalse(store.exists())
    }
}
