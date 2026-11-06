package dev.lucy.phone.configuration

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val ITERATIONS = 210_000
    fun random(bytes: Int = 16): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    fun hash(secret: String, salt: String): String {
        val spec = PBEKeySpec(secret.toCharArray(), Base64.getUrlDecoder().decode(salt), ITERATIONS, 256)
        return try { Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded) }
        finally { spec.clearPassword() }
    }
    fun verify(secret: String, salt: String, hash: String) = MessageDigest.isEqual(hash(secret, salt).toByteArray(), hash.toByteArray())
    fun validPin(pin: String) = pin.length in 8..64 && pin.all { it in '0'..'9' }
}

const val ADMIN_SESSION_TIMEOUT_MILLIS = 30_000L
fun adminSessionActive(now: Long, lastUsed: Long) = now - lastUsed < ADMIN_SESSION_TIMEOUT_MILLIS

class AdminAuth(private val context: Context, private val policies: PolicyStore) {
    private val file = AtomicFile(File(context.filesDir, "admin.json"))
    private val totpSecrets = TotpSecretStore(context)
    private var token: String? = null
    private var lastUsed = 0L
    private var pendingTotpSecret: ByteArray? = null
    private fun now() = SystemClock.elapsedRealtime()
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private fun read() = if (file.baseFile.exists()) JSONObject(file.openRead().bufferedReader().use { it.readText() }) else JSONObject()
    private fun write(json: JSONObject) {
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    @Synchronized fun configured() = read().has("pinHash")
    @Synchronized fun setup(pin: String): String {
        check(!configured() && !policies.configured()) { "Administrator is already configured; use recovery" }
        return replace(pin, preserveTotp = false)
    }
    private fun replace(pin: String, preserveTotp: Boolean): String {
        require(Passwords.validPin(pin)) { "Use 8–64 digits for the administrator PIN" }
        val code = Passwords.random(20)
        val salt = Passwords.random()
        val codeSalt = Passwords.random()
        val previous = read()
        val replacement = JSONObject().put("pinSalt", salt).put("pinHash", Passwords.hash(pin, salt))
            .put("recoverySalt", codeSalt).put("recoveryHash", Passwords.hash(code, codeSalt))
        if (preserveTotp && previous.optBoolean("totpEnabled") && totpSecrets.exists()) {
            replacement.put("totpEnabled", true).put("lastTotpStep", previous.optLong("lastTotpStep", -1))
        } else {
            pendingTotpSecret?.fill(0); pendingTotpSecret = null
            if (totpSecrets.exists()) totpSecrets.clear()
        }
        write(replacement)
        token = null
        policies.update { state ->
            val onboarding = state.optJSONObject("onboarding") ?: JSONObject()
            onboarding.put("recoverySaved", false)
            state.put("onboarding", onboarding)
        }
        policies.log("Administrator credentials created or rotated")
        return code
    }
    private fun attempts(json: JSONObject): AttemptState {
        val state = AttemptState(json.optInt("failures"), json.optLong("deadline"), json.optInt("boot"))
        val rebased = state.rebase(now(), boot())
        if (rebased != state) saveAttempts(json, rebased)
        return rebased
    }
    private fun saveAttempts(json: JSONObject, state: AttemptState) {
        json.put("failures", state.failures).put("deadline", state.deadline).put("boot", state.boot)
        write(json)
    }
    private fun availableAttempt(json: JSONObject): AttemptState {
        check(json.has("pinHash")) { "Set up the administrator first" }
        val attempts = attempts(json)
        val remaining = attempts.remaining(now())
        check(remaining == 0L) { "Try again in ${(remaining + 999) / 1000} seconds" }
        return attempts
    }
    private fun failedAttempt(json: JSONObject, attempts: AttemptState): Nothing {
        saveAttempts(json, attempts.failed(now(), boot()))
        policies.log("Administrator authentication failed")
        error("Incorrect credential")
    }
    private fun checkSecret(secret: String, recovery: Boolean) {
        val json = read()
        val attempts = availableAttempt(json)
        val prefix = if (recovery) "recovery" else "pin"
        if (!Passwords.verify(secret, json.getString(prefix + "Salt"), json.getString(prefix + "Hash"))) {
            failedAttempt(json, attempts)
        }
        saveAttempts(json, AttemptState(boot = boot()))
    }
    private fun newSession() = UUID.randomUUID().toString().also { token = it; lastUsed = now() }
    @Synchronized fun unlock(pin: String): String {
        checkSecret(pin, false)
        return newSession()
    }
    @Synchronized fun totpConfigured() = read().optBoolean("totpEnabled")
    @Synchronized fun beginTotpEnrollment(session: String): TotpEnrollment {
        requireSession(session)
        pendingTotpSecret?.fill(0)
        val secret = Totp.newSecret()
        pendingTotpSecret = secret
        return TotpEnrollment(Totp.provisioningUri(secret), Totp.formattedSetupKey(secret))
    }
    @Synchronized fun confirmTotpEnrollment(session: String, code: String) {
        requireSession(session)
        val secret = pendingTotpSecret ?: error("Start authenticator setup again")
        val json = read()
        val attempts = availableAttempt(json)
        val matched = Totp.matchingStep(secret, code.trim()) ?: failedAttempt(json, attempts)
        try {
            totpSecrets.write(secret)
            json.put("totpEnabled", true).put("lastTotpStep", matched)
            saveAttempts(json, AttemptState(boot = boot()))
        } finally {
            secret.fill(0)
            pendingTotpSecret = null
        }
        policies.log("Administrator authenticator enabled")
    }
    @Synchronized fun unlockTotp(code: String): String {
        val json = read()
        check(json.optBoolean("totpEnabled")) { "Authenticator access is not configured" }
        check(totpSecrets.exists()) { "Authenticator unavailable; use the backup PIN" }
        val attempts = availableAttempt(json)
        val secret = runCatching { totpSecrets.read() }.getOrElse { error("Authenticator unavailable; use the backup PIN") }
        val matched = try {
            Totp.matchingStep(secret, code.trim(), lastAcceptedStep = json.optLong("lastTotpStep", -1))
                ?: failedAttempt(json, attempts)
        } finally { secret.fill(0) }
        json.put("lastTotpStep", matched)
        saveAttempts(json, AttemptState(boot = boot()))
        return newSession()
    }
    @Synchronized fun disableTotp(session: String) {
        requireSession(session)
        pendingTotpSecret?.fill(0); pendingTotpSecret = null
        if (totpSecrets.exists()) totpSecrets.clear()
        val json = read().apply { remove("totpEnabled"); remove("lastTotpStep") }
        write(json)
        policies.log("Administrator authenticator disabled")
    }
    @Synchronized fun cancelTotpEnrollment() {
        pendingTotpSecret?.fill(0); pendingTotpSecret = null
    }
    @Synchronized fun recover(code: String, newPin: String): String {
        require(Passwords.validPin(newPin)) { "Use at least eight digits" }
        checkSecret(code.trim(), true)
        return replace(newPin, preserveTotp = false)
    }
    @Synchronized fun changePin(session: String, newPin: String): String { requireSession(session); return replace(newPin, preserveTotp = true) }
    @Synchronized fun requireSession(session: String) {
        check(validSession(session)) { "Administrator session expired; authenticate again" }
        lastUsed = now()
    }
    @Synchronized fun validSession(session: String?) = session != null && session == token && adminSessionActive(now(), lastUsed)
    @Synchronized fun lock() { token = null; pendingTotpSecret?.fill(0); pendingTotpSecret = null }
}
