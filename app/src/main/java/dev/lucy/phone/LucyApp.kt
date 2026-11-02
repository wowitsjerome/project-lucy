package dev.lucy.phone

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.UserManager
import dev.lucy.phone.configuration.AdminAuth
import dev.lucy.phone.configuration.PolicyStore
import dev.lucy.phone.policy.PolicyEngine
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

class LucyApp : Application() {
    val executor = Executors.newSingleThreadExecutor()
    val updates = MutableStateFlow(0L)
    // Task-safe return destination only, never an authentication grant. Cleared on process death.
    var adminReturnPage: String? = null
    val store by lazy { PolicyStore(this) }
    val auth by lazy { AdminAuth(this, store) }
    val engine by lazy { PolicyEngine(this) }
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
