package dev.lucy.phone.configuration

data class ApplyResult(val applied: Boolean, val restored: Boolean, val error: String? = null)

/** An existing managed installation stays activated even if a later update needs attention. */
fun ApplyResult.keepActivated(hadPreviousPolicy: Boolean) = applied || hadPreviousPolicy

/** Platform policies are not atomic: verification and compensating rollback are mandatory. */
object PolicyTransaction {
    fun <T> run(candidate: T, previous: T?, apply: (T) -> Unit, verify: (T) -> List<String>): ApplyResult {
        return try {
            apply(candidate)
            val errors = verify(candidate)
            check(errors.isEmpty()) { errors.joinToString("; ") }
            ApplyResult(applied = true, restored = false)
        } catch (failure: Exception) {
            val restored = previous != null && runCatching {
                apply(previous)
                check(verify(previous).isEmpty())
            }.isSuccess
            ApplyResult(false, restored, failure.message ?: "Policy application failed")
        }
    }
}
