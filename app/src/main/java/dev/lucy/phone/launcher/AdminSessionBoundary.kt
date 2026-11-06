package dev.lucy.phone.launcher

/**
 * Distinguishes an administrator-requested external tool from an unrelated app switch.
 * The grant is single-use: only the next Activity pause may preserve the in-memory session.
 */
internal class AdminSessionBoundary {
    private var expectedExternalPause = false

    fun expectExternalPause() { expectedExternalPause = true }
    fun cancelExternalPause() { expectedExternalPause = false }

    fun shouldPreserveAdminOnPause(pausingAdministration: Boolean): Boolean {
        val preserve = expectedExternalPause
        expectedExternalPause = false
        return pausingAdministration && preserve
    }
}

internal data class AdminActivityHandoff(val session: String, val page: String, val deadline: Long)

internal class AdminActivityHandoffStore(private val now: () -> Long) {
    private var handoff: AdminActivityHandoff? = null

    @Synchronized fun prepare(session: String, page: String, lifetimeMillis: Long = 15_000) {
        handoff = AdminActivityHandoff(session, page, now() + lifetimeMillis)
    }

    @Synchronized fun consume(): AdminActivityHandoff? {
        val candidate = handoff
        handoff = null
        return candidate?.takeIf { now() < it.deadline }
    }

    @Synchronized fun clear() { handoff = null }
}
