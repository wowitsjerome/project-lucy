package dev.lucy.phone

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.os.UserManager
import dev.lucy.phone.configuration.AdminAuth
import dev.lucy.phone.configuration.PolicyStore
import dev.lucy.phone.launcher.AdminActivityHandoff
import dev.lucy.phone.launcher.AdminActivityHandoffStore
import dev.lucy.phone.launcher.AppearanceStore
import dev.lucy.phone.policy.PolicyEngine
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

class LucyApp : Application() {
    val executor = Executors.newSingleThreadExecutor()
    val updates = MutableStateFlow(0L)
    // Task-safe return destination only, never an authentication grant. Cleared on process death.
    var adminReturnPage: String? = null
    private val adminActivityHandoff = AdminActivityHandoffStore(SystemClock::elapsedRealtime)
    val store by lazy { PolicyStore(this) }
    val auth by lazy { AdminAuth(this, store) }
    val appearance by lazy { AppearanceStore(this) }
    val engine by lazy { PolicyEngine(this) }
    internal fun prepareAdminActivityHandoff(session: String, page: String) {
        // Only bridges Lucy's immediate lock-task exit/recreation; external tools otherwise rely
        // on the existing Activity instance and process death always clears this marker.
        adminActivityHandoff.prepare(session, page)
    }
    internal fun consumeAdminActivityHandoff(): AdminActivityHandoff? = adminActivityHandoff.consume()
    internal fun clearAdminActivityHandoff() = adminActivityHandoff.clear()
    override fun onCreate() {
        super.onCreate()
        executor.execute {
            runCatching { engine.reconcile(restoreMaintenance = true) }.onFailure { engine.report(it) }
            if (engine.hasTimedSession()) runCatching {
                startForegroundService(android.content.Intent(this, dev.lucy.phone.policy.MaintenanceService::class.java))
            }.onFailure { engine.report(it) }
            changed()
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refreshNetwork() }
            override fun onLost(network: Network) { refreshNetwork() }
            private fun refreshNetwork() {
                executor.execute {
                    if (getSystemService(UserManager::class.java).isUserUnlocked) runCatching { engine.checkFilter() }
                    changed()
                }
            }
        })
    }
    fun changed() { updates.value += 1 }
}
