package dev.lucy.phone.configuration

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TotpEnrollment(val uri: String, val setupKey: String)

object Totp {
    const val DIGITS = 6
    const val PERIOD_SECONDS = 30L
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun newSecret(bytes: Int = 20): ByteArray = ByteArray(bytes).also { SecureRandom().nextBytes(it) }

    fun base32(secret: ByteArray): String {
        val result = StringBuilder((secret.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        secret.forEach { value ->
            buffer = (buffer shl 8) or (value.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                result.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) result.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return result.toString()
    }

    fun formattedSetupKey(secret: ByteArray) = base32(secret).chunked(4).joinToString(" ")

    fun provisioningUri(secret: ByteArray, account: String = "Administrator", issuer: String = "Lucy"): String {
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "otpauth://totp/${encode("$issuer:$account")}?secret=${base32(secret)}&issuer=${encode(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
    }

    fun code(secret: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = digest.last().toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    fun matchingStep(secret: ByteArray, entered: String, nowMillis: Long = System.currentTimeMillis(),
        window: Int = 1, lastAcceptedStep: Long = -1): Long? {
        if (entered.length != DIGITS || entered.any { it !in '0'..'9' }) return null
        val current = nowMillis / 1000 / PERIOD_SECONDS
        return (-window..window).map { current + it }.filter { it > lastAcceptedStep }.firstOrNull { step ->
            MessageDigest.isEqual(code(secret, step).toByteArray(), entered.toByteArray())
        }
    }
}
