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

class AdminAuth(private val context: Context, private val policies: PolicyStore) {
    private val file = AtomicFile(File(context.filesDir, "admin.json"))
    private var token: String? = null
    private var lastUsed = 0L
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
        return replace(pin)
    }
    private fun replace(pin: String): String {
        require(Passwords.validPin(pin)) { "Use 8–64 digits for the administrator PIN" }
        val code = Passwords.random(20)
        val salt = Passwords.random()
        val codeSalt = Passwords.random()
        write(JSONObject().put("pinSalt", salt).put("pinHash", Passwords.hash(pin, salt))
            .put("recoverySalt", codeSalt).put("recoveryHash", Passwords.hash(code, codeSalt)))
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
    private fun checkSecret(secret: String, recovery: Boolean) {
        val json = read()
        check(json.has("pinHash")) { "Set up the administrator first" }
        val attempts = attempts(json)
        val remaining = attempts.remaining(now())
        check(remaining == 0L) { "Try again in ${(remaining + 999) / 1000} seconds" }
        val prefix = if (recovery) "recovery" else "pin"
        if (!Passwords.verify(secret, json.getString(prefix + "Salt"), json.getString(prefix + "Hash"))) {
            saveAttempts(json, attempts.failed(now(), boot()))
            policies.log("Administrator authentication failed")
            error("Incorrect credential")
        }
        saveAttempts(json, AttemptState(boot = boot()))
    }
    @Synchronized fun unlock(pin: String): String {
        checkSecret(pin, false)
        return UUID.randomUUID().toString().also { token = it; lastUsed = now() }
    }
    @Synchronized fun recover(code: String, newPin: String): String {
        require(Passwords.validPin(newPin)) { "Use at least eight digits" }
        checkSecret(code.trim(), true)
        return replace(newPin)
    }
    @Synchronized fun changePin(session: String, newPin: String): String { requireSession(session); return replace(newPin) }
    @Synchronized fun requireSession(session: String) {
        check(validSession(session)) { "Administrator session expired; enter your PIN again" }
        lastUsed = now()
    }
    @Synchronized fun validSession(session: String?) = session != null && session == token && now() - lastUsed < 120_000
    @Synchronized fun lock() { token = null }
}
