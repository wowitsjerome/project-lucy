package dev.lucy.phone.configuration

import org.json.JSONArray
import org.json.JSONObject

data class AppEntry(val packageName: String, val activity: String, val label: String, val essential: Boolean = false)

data class DevicePolicy(
    val revision: Long = 1,
    val baseline: String,
    val apps: List<AppEntry>,
    val support: Map<String, String>,
    val activitySupport: Set<String>,
    val blocked: Set<String>,
    val dnsHost: String = DNS_HOST,
) {
    fun lockPackages(ownPackage: String) = (apps.map { it.packageName } + activitySupport + ownPackage).toSet()
    fun requiringActivitySupport(packages: Set<String>, purpose: String): DevicePolicy {
        if (packages.isEmpty()) return this
        return copy(support = support + packages.associateWith { purpose }, activitySupport = activitySupport + packages,
            blocked = blocked - packages)
    }
    /** Removing an optional app during supervised maintenance is also a request to remove it from daily policy. */
    fun pruningMissingOptionalApps(installed: Set<String>): DevicePolicy {
        val remaining = apps.filter { it.essential || it.packageName in installed }
        return if (remaining.isNotEmpty()) copy(apps = remaining) else this
    }
    fun toJson() = JSONObject().apply {
        put("schemaVersion", 1); put("revision", revision); put("deviceBaselineId", baseline)
        put("visibleApps", JSONArray(apps.map { app -> JSONObject().apply {
            put("package", app.packageName); put("activity", app.activity); put("label", app.label); put("essential", app.essential)
        } }))
        put("supportPackages", JSONObject(support)); put("activitySupport", JSONArray(activitySupport.toList()))
        put("blockedPackages", JSONArray(blocked.toList())); put("privateDnsHost", dnsHost)
    }
    companion object {
        const val DNS_HOST = "family.cloudflare-dns.com"
        fun fromJson(json: JSONObject): DevicePolicy {
            require(json.getInt("schemaVersion") == 1) { "Unsupported policy schema" }
            val apps = json.getJSONArray("visibleApps")
            val support = json.getJSONObject("supportPackages")
            return DevicePolicy(json.getLong("revision"), json.getString("deviceBaselineId"),
                (0 until apps.length()).map { i -> apps.getJSONObject(i).let {
                    AppEntry(it.getString("package"), it.getString("activity"), it.getString("label"), it.getBoolean("essential"))
                } }, support.keys().asSequence().associateWith { support.getString(it) },
                json.getJSONArray("activitySupport").strings(), json.getJSONArray("blockedPackages").strings(), json.getString("privateDnsHost"))
        }
    }
}

fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()

object PolicyRules {
    const val CHROME_PACKAGE = "com.android.chrome"

    /** Chrome is eligible for explicit selection, never automatically approved by this exception. */
    fun forbiddenPackages(discovered: Set<String>): Set<String> =
        (discovered + setOf("com.android.settings", "com.android.vending",
            "com.sec.android.app.samsungapps", "com.sec.android.app.sbrowser")) - CHROME_PACKAGE

    fun validate(policy: DevicePolicy, essential: Set<String>, own: String, forbidden: Set<String>): List<String> = buildList {
        val visible = policy.apps.map { it.packageName }
        if (visible.size != visible.toSet().size) add("Duplicate app packages")
        if (!visible.containsAll(essential)) add("Required phone applications cannot be removed")
        if (policy.apps.isEmpty()) add("Select at least one application")
        if (policy.apps.any { it.label.isBlank() || it.activity.isBlank() }) add("Application metadata is incomplete")
        if (policy.lockPackages(own).intersect(forbidden).isNotEmpty()) add("Other browsers, stores, and unrestricted Settings cannot be approved")
        if (policy.blocked.intersect(policy.lockPackages(own) + policy.support.keys).isNotEmpty()) add("A required package is also blocked")
        if (policy.dnsHost != DevicePolicy.DNS_HOST) add("V1 requires the Families resolver")
    }
}

data class MaintenanceWindow(val deadline: Long, val boot: Int) {
    fun active(now: Long, currentBoot: Int) = boot == currentBoot && now < deadline
}

data class AttemptState(val failures: Int = 0, val deadline: Long = 0, val boot: Int = 0) {
    fun penaltyMillis() = if (failures < 5) 0L else minOf(900_000L, 30_000L * (1L shl minOf(5, failures - 5)))
    fun rebase(now: Long, currentBoot: Int) = if (boot == currentBoot) this else copy(deadline = now + penaltyMillis(), boot = currentBoot)
    fun remaining(now: Long) = (deadline - now).coerceAtLeast(0)
    fun failed(now: Long, currentBoot: Int): AttemptState {
        val next = copy(failures = minOf(failures + 1, 100), boot = currentBoot)
        return next.copy(deadline = now + next.penaltyMillis())
    }
}
