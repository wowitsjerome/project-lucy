package dev.lucy.phone.filtering

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import android.os.Bundle
import android.os.Build
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ProbeResult(val label: String, val passed: Boolean, val detail: String)
data class FilterResult(val checkedAt: Long, val passed: Boolean, val detail: String, val controlPassed: Boolean,
                        val network: String, val checks: List<ProbeResult>)

class DnsFilter(private val context: Context) {
    companion object { const val LOCAL_PACKAGE = "dnsfilter.android" }
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callbacks = Executors.newCachedThreadPool()
    fun localAvailable() = runCatching {
        context.packageManager.getApplicationInfo(LOCAL_PACKAGE, 0)
    }.isSuccess
    fun configured(admin: ComponentName, host: String): Boolean =
        if (localAvailable()) dpm.getAlwaysOnVpnPackage(admin) == LOCAL_PACKAGE &&
            android.provider.Settings.Global.getString(context.contentResolver, "private_dns_mode") == "off"
        else dpm.getGlobalPrivateDnsHost(admin) == host &&
            dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
    fun networkId() = connectivity.activeNetwork?.toString().orEmpty()
    fun networkDescription(): String {
        val network = connectivity.activeNetwork ?: return "No network connected. Open Wi-Fi settings or use cellular."
        val caps = connectivity.getNetworkCapabilities(network)
        val kind = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            else -> "Network"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return "$kind connected · " + if (validated) "Android reports internet access" else "Internet access is not verified; check Wi-Fi and Private DNS"
    }
    fun configure(admin: ComponentName, host: String) {
        if (localAvailable()) { enforceStrict(admin, host); return }
        if (configured(admin, host)) return
        enforceStrict(admin, host)
    }
    fun enforceStrict(admin: ComponentName, host: String) {
        if (localAvailable()) {
            // DNS-only VPN: lockdown would block ordinary non-DNS traffic.
            // Enforce restart via always-on; probes detect a stopped/bypassed filter.
            dpm.setApplicationHidden(admin, LOCAL_PACKAGE, false)
            dpm.setUninstallBlocked(admin, LOCAL_PACKAGE, true)
            dpm.setUserControlDisabledPackages(admin, listOf(context.packageName, LOCAL_PACKAGE))
            if (Build.VERSION.SDK_INT >= 33) dpm.setPermissionGrantState(admin, LOCAL_PACKAGE,
                android.Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            dpm.setApplicationRestrictions(admin, "com.android.chrome", Bundle().apply {
                putString("DnsOverHttpsMode", "off")
            })
            if (dpm.getAlwaysOnVpnPackage(admin) != LOCAL_PACKAGE)
                dpm.setAlwaysOnVpnPackage(admin, LOCAL_PACKAGE, false)
            @Suppress("DEPRECATION")
            dpm.setGlobalSetting(admin, "private_dns_mode", "off")
            check(configured(admin, host)) { "Local filtering policy did not apply" }
            return
        }
        // AOSP API 30–36 explicitly allowlists these keys for Device Owner setGlobalSetting.
        // The convenience DNS setter refuses an unreachable provider BEFORE storing strict mode.
        // Restoration must not leave an expired exception using unfiltered DNS during an outage.
        @Suppress("DEPRECATION")
        dpm.setGlobalSetting(admin, "private_dns_specifier", host)
        @Suppress("DEPRECATION")
        dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
        check(dpm.getGlobalPrivateDnsHost(admin) == host &&
            dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
            "Android did not restore strict filtered DNS. Keep administrator custody and retry."
        }
    }
    fun temporaryInternet(admin: ComponentName) {
        check(!localAvailable()) { "Temporary unfiltered internet is unavailable with the local filter installed" }
        check(dpm.setGlobalPrivateDnsModeOpportunistic(admin) == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR &&
            dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC) {
            "Android could not enable temporary internet"
        }
    }
    private fun query(host: String): List<InetAddress> {
        val network = connectivity.activeNetwork ?: error("No active network")
        val complete = CountDownLatch(1)
        var answer: List<InetAddress> = emptyList()
        var failure: String? = null
        val cancellation = CancellationSignal()
        DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_NO_CACHE_LOOKUP, callbacks, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(result: List<InetAddress>, rcode: Int) {
                    if (rcode != 0) failure = "DNS response $rcode" else answer = result
                    complete.countDown()
                }
                override fun onError(error: DnsResolver.DnsException) { failure = "$host: DNS query failed (code ${error.code})"; complete.countDown() }
            })
        if (!complete.await(6, TimeUnit.SECONDS)) { cancellation.cancel(); error("DNS probe timed out") }
        failure?.let { error(it) }
        return answer
    }
    fun probe(): FilterResult {
        val network = networkId()
        val checks = mutableListOf<ProbeResult>()
        fun test(label: String, block: () -> String): Boolean {
            val result = runCatching(block)
            checks += ProbeResult(label, result.isSuccess, result.getOrElse { it.message ?: "Check failed" })
            return result.isSuccess
        }
        val controlPassed = test("Normal internet") {
            val normal = query("example.com")
            check(normal.isNotEmpty() && normal.none { it.isAnyLocalAddress || it.isLoopbackAddress }) { "Allowed-domain check failed" }
            val connection = URL("https://example.com").openConnection() as javax.net.ssl.HttpsURLConnection
            try {
                connection.connectTimeout = 6_000; connection.readTimeout = 6_000
                check(connection.responseCode in 200..399) { "Allowed website could not be reached" }
            } finally { connection.disconnect() }
            "example.com resolves and HTTPS works"
        }
        val targets = mutableListOf("Adult-category blocking" to "nudity.testcategory.com", "Malware blocking" to "malware.testcategory.com")
        if (localAvailable()) targets += listOf("Custom domain blocking" to "blocked.lucy.test",
            "Custom subdomain blocking" to "sub.blocked.lucy.test")
        for ((label, host) in targets) {
            if (!controlPassed) checks += ProbeResult(label, false, "Not assessed: normal internet must work first")
            else test(label) {
                val result = query(host)
                check(result.isNotEmpty() && result.all { it.isAnyLocalAddress || it.isLoopbackAddress }) { "$host: category blocking could not be verified" }
                "$host returned a blocking address"
            }
        }
        val passed = checks.all { it.passed } && network.isNotEmpty() && network == networkId()
        return FilterResult(System.currentTimeMillis(), passed,
            if (passed) "Normal internet and all domain checks passed" else "Filtering is not verified. An offline phone is not a filtering pass.",
            controlPassed, network, checks)
    }
}
