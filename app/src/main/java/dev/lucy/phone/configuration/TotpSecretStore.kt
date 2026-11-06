package dev.lucy.phone.configuration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TotpSecretStore(context: Context, private val alias: String = "lucy-admin-totp-v1",
    fileName: String = "admin-totp.json") {
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    @Synchronized fun exists() = file.baseFile.exists()

    @Synchronized fun write(secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject().put("version", 1)
            .put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(secret)))
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray()); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }

    @Synchronized fun read(): ByteArray {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        check(json.getInt("version") == 1) { "Unsupported authenticator secret format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(alias, null) as? SecretKey
            ?: error("Authenticator encryption key is unavailable"),
            GCMParameterSpec(128, Base64.getDecoder().decode(json.getString("iv"))))
        return cipher.doFinal(Base64.getDecoder().decode(json.getString("ciphertext")))
    }

    @Synchronized fun clear() {
        file.baseFile.delete()
        keyStore.deleteEntry(alias)
    }
}
