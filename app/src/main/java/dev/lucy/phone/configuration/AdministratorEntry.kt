package dev.lucy.phone.configuration

/** Unknown or unreadable state must never be interpreted as permission to create credentials. */
enum class AdministratorEntry {
    LOADING, SETUP, LOGIN, UNAVAILABLE;

    companion object {
        fun resolve(configured: Boolean?, managed: Boolean, readFailed: Boolean = false): AdministratorEntry = when {
            readFailed -> UNAVAILABLE
            configured == null -> LOADING
            configured -> LOGIN
            managed -> UNAVAILABLE
            else -> SETUP
        }
    }
}
