package dev.lucy.phone.policy

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import dev.lucy.phone.BuildConfig
import dev.lucy.phone.LucyApp
import dev.lucy.phone.configuration.*
import dev.lucy.phone.filtering.DnsFilter
import dev.lucy.phone.launcher.HomeActivity
import org.json.JSONArray
import org.json.JSONObject

class PolicyEngine(private val app: LucyApp) {
    val admin = ComponentName(app, LucyAdminReceiver::class.java)
    val dpm: DevicePolicyManager = app.getSystemService(DevicePolicyManager::class.java)
    val inventory = PackageInventory(app)
    private val dns = DnsFilter(app)
    private val store get() = app.store
    val isOwner get() = dpm.isDeviceOwnerApp(app.packageName)
    val restrictions get() = listOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_USER_SWITCH, UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
        UserManager.DISALLOW_CONFIG_VPN, UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_CREATE_WINDOWS) + if (BuildConfig.ALLOW_DEBUGGING) emptyList() else listOf(UserManager.DISALLOW_DEBUGGING_FEATURES)
    private val maintenanceRelaxed = listOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    private val features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
    fun bootCount() = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)
    fun inMaintenance() = store.maintenance()?.active(SystemClock.elapsedRealtime(), bootCount()) == true
    private fun internetWindow() = store.read().optJSONObject("temporaryInternet")?.let {
        MaintenanceWindow(it.getLong("deadline"), it.getInt("boot"))
    }
    fun temporaryInternetActive() = internetWindow()?.active(SystemClock.elapsedRealtime(), bootCount()) == true
    fun hasTimedSession() = store.maintenance() != null || internetWindow() != null || store.read().optBoolean("restoreRequired")
    fun remainingMinutes(internet: Boolean = false): Long {
        val lease = if (internet) internetWindow() else store.maintenance()
        return if (lease?.active(SystemClock.elapsedRealtime(), bootCount()) == true)
            ((lease.deadline - SystemClock.elapsedRealtime() + 59_999) / 60_000).coerceAtLeast(0) else 0
    }
    fun networkDescription() = dns.networkDescription()
    fun draftPolicy() = store.draft() ?: currentPolicy()
    fun filterPassed(): Boolean {
        if (!isOwner || temporaryInternetActive()) return false
        val filter = store.read().optJSONObject("filter") ?: return false
        return filter.optBoolean("passed") && System.currentTimeMillis() - filter.optLong("checkedAt") in 0..300_000 &&
            filter.optString("network") == dns.networkId() && dns.networkId().isNotEmpty() &&
            dns.configured(admin, DevicePolicy.DNS_HOST)
    }
    fun onboardingBlockers(): List<String> {
        val checklist = store.onboarding()
        val reviewed = checklist.optJSONArray("reviewedApps")?.strings()
        return Onboarding.blockers(isOwner, app.getSystemService(KeyguardManager::class.java).isDeviceSecure,
            canScheduleMaintenance(), reviewed == draftPolicy().apps.map { it.packageName }.toSet(),
            checklist.optBoolean("recoverySaved"), checklist.optBoolean("navigationTested"), filterPassed())
    }
    @Synchronized fun acknowledge(session: String, key: String, value: Boolean) {
        app.auth.requireSession(session)
        require(key in setOf("appsReviewed", "recoverySaved", "navigationTested"))
        store.update { state ->
            val checklist = state.optJSONObject("onboarding") ?: JSONObject()
            if (key == "appsReviewed") {
                if (value) checklist.put("reviewedApps", JSONArray(draftPolicy().apps.map { it.packageName }))
                else checklist.remove("reviewedApps")
            } else checklist.put(key, value)
            state.put("onboarding", checklist)
        }
        app.changed()
    }
    fun currentPolicy(): DevicePolicy {
        val saved = store.policy() ?: store.read().optJSONObject("pending")?.let(DevicePolicy::fromJson)
            ?: store.read().optJSONObject("launcherPreview")?.let(DevicePolicy::fromJson) ?: inventory.initial()
        val required = inventory.withRequiredSystemSupport(saved)
        val support = required.support + inventory.criticalSupport()
        return required.copy(support = support, blocked = required.blocked - support.keys - required.activitySupport)
    }
    fun lockTaskRunning() = app.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
    fun canScheduleMaintenance() = Build.VERSION.SDK_INT < 31 || app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun expiryIntent() = PendingIntent.getBroadcast(app, 902, Intent(app, MaintenanceExpiryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun internetExpiryIntent() = PendingIntent.getBroadcast(app, 903, Intent(app, MaintenanceExpiryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun selectedPolicy(selected: Set<String>): DevicePolicy {
        val prior = currentPolicy()
        val catalog = inventory.apps(prior).associateBy { it.packageName }
        require(selected.all { it in catalog && inventory.exists(it) }) { "A selected app is missing. Refresh the app list." }
        val apps = selected.map { catalog.getValue(it) }
        val newlyAllowed = selected - prior.apps.map { it.packageName }.toSet()
        val requiredOnHome = apps.filter { it.essential }.map { it.packageName }.toSet()
        val homePackages = prior.homePackages.intersect(selected) + newlyAllowed + requiredOnHome
        val candidate = prior.copy(revision = prior.revision + 1, apps = apps, homePackages = homePackages)
            .let { it.copy(blocked = inventory.blockedFor(it)) }
        val required = prior.apps.filter { it.essential }.map { it.packageName }.toSet() + inventory.essentialPackages()
        val errors = PolicyRules.validate(candidate, required, app.packageName, inventory.forbidden())
        require(errors.isEmpty()) { errors.joinToString("; ") }
        return candidate
    }
    @Synchronized fun saveHomeSelection(selected: Set<String>) {
        val prior = currentPolicy()
        val allowed = prior.apps.map { it.packageName }.toSet()
        val required = prior.apps.filter { it.essential }.map { it.packageName }.toSet()
        require(selected.all { it in allowed }) { "Only administrator-approved apps can appear on Home" }
        require(selected.containsAll(required)) { "Required phone applications must remain on Home" }
        val candidate = prior.copy(homePackages = selected)
        store.update { state ->
            val key = if (state.optJSONObject("effective") != null) "effective" else "launcherPreview"
            state.put(key, candidate.toJson())
        }
        store.log("Updated Home app visibility")
        app.changed()
    }
    @Synchronized fun saveDraft(session: String, selected: Set<String>) {
        app.auth.requireSession(session)
        val candidate = selectedPolicy(selected)
        store.update { it.put("draft", candidate.toJson()) }
        app.changed()
    }
    @Synchronized fun applyDraft(session: String) {
        app.auth.requireSession(session)
        val selected = draftPolicy().apps.map { it.packageName }.toSet()
        val candidate = selectedPolicy(selected)
        if (!isOwner) { saveLauncherList(session, selected); store.update { it.remove("draft") }; return }
        check(store.onboarding().optJSONArray("reviewedApps")?.strings() == selected) { "Review the selected apps, accounts, and permissions before applying the draft" }
        if (!store.configured()) {
            val blockers = onboardingBlockers()
            check(blockers.isEmpty()) { blockers.joinToString("; ") }
        }
        if (inMaintenance()) {
            // Validate before ending the lease; the draft survives failed application/restoration.
            restoreTemporaryInternet()
            store.update { it.put("restoreRequired", true); it.remove("maintenance") }
        }
        try {
            activate(session, candidate.apps.map { it.packageName }.toSet())
            store.update { it.remove("draft"); it.remove("restoreRequired") }
        } catch (failure: Exception) {
            if (store.configured()) runCatching { reconcile(restoreMaintenance = true) }.onFailure(::report)
            throw failure
        } finally {
            if (!hasTimedSession()) {
                app.getSystemService(AlarmManager::class.java).cancel(expiryIntent())
            }
            returnHome(); app.changed()
        }
    }

    @Synchronized fun saveLauncherList(session: String, selected: Set<String>) {
        app.auth.requireSession(session)
        check(!store.configured()) { "Use managed policy controls" }
        val candidate = selectedPolicy(selected)
        require(candidate.apps.isNotEmpty()) { "Select at least one application" }
        store.update { it.put("launcherPreview", candidate.toJson()) }
        app.changed()
    }

    @Synchronized fun activate(session: String, selected: Set<String>) {
        app.auth.requireSession(session)
        check(app.auth.configured()) { "Administrator credentials are required" }
        check(isOwner) { "Provision Lucy as Device Owner in the dedicated emulator first" }
        check(app.getSystemService(KeyguardManager::class.java).isDeviceSecure) { "Set a device screen-lock PIN in Settings before activating management" }
        check(canScheduleMaintenance()) { "Allow Lucy's maintenance timer in Alarms & reminders before activating management" }
        val prior = currentPolicy()
        val catalog = inventory.apps(prior).associateBy { it.packageName }
        val apps = selected.mapNotNull(catalog::get)
        val newlyAllowed = selected - prior.apps.map { it.packageName }.toSet()
        val requiredOnHome = apps.filter { it.essential }.map { it.packageName }.toSet()
        val homePackages = prior.homePackages.intersect(selected) + newlyAllowed + requiredOnHome
        val candidate = prior.copy(revision = prior.revision + 1, apps = apps, homePackages = homePackages)
            .let { it.copy(blocked = inventory.blockedFor(it)) }
        val essential = prior.apps.filter { it.essential }.map { it.packageName }.toSet() + inventory.essentialPackages()
        val errors = PolicyRules.validate(candidate, essential, app.packageName, inventory.forbidden())
        require(errors.isEmpty()) { errors.joinToString("; ") }
        check(candidate.apps.all { inventory.exists(it.packageName) }) { "An approved application is no longer installed" }
        check(!inMaintenance()) { "Finish maintenance before changing the daily app list" }
        if (!store.configured()) {
            check(selected == draftPolicy().apps.map { it.packageName }.toSet()) { "Save and review this app selection first" }
            check(onboardingBlockers().isEmpty()) { onboardingBlockers().joinToString("; ") }
        }
        commit(candidate)
    }

    private fun commit(candidate: DevicePolicy) {
        val previous = store.policy()
        store.update { it.put("pending", candidate.toJson()).put("errors", JSONArray(listOf("Applying policy"))) }
        try {
            val result = PolicyTransaction.run(candidate, previous, ::apply, ::verify)
            if (!result.applied) {
                store.update {
                    if (result.restored) it.remove("pending")
                    if (!result.keepActivated(previous != null)) it.remove("activated")
                }
                store.setErrors(listOf("Policy not verified: ${result.error}", if (result.restored) "Previous policy restored; review and retry" else "Administrator attention required"))
                store.log("Policy application failed; rollback=${result.restored}")
                error(result.error ?: "Policy application failed")
            }
            store.update { it.put("activated", true).put("effective", candidate.toJson()).put("lastVerifiedRevision", candidate.revision)
                .put("errors", JSONArray()).remove("pending") }
            store.log("Applied policy revision ${candidate.revision}")
            checkFilter()
        } finally { app.changed() }
    }

    private fun apply(policy: DevicePolicy) {
        check(isOwner) { "Device Owner access missing" }
        for (pkg in policy.lockPackages(app.packageName) + policy.support.keys) {
            if (pkg == app.packageName || !inventory.exists(pkg)) continue
            if (dpm.isApplicationHidden(admin, pkg)) check(dpm.setApplicationHidden(admin, pkg, false)) { "Cannot enable $pkg" }
            if (dpm.isPackageSuspended(admin, pkg)) check(dpm.setPackagesSuspended(admin, arrayOf(pkg), false).isEmpty()) { "Cannot resume $pkg" }
        }
        val home = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(app, HomeActivity::class.java))
        dpm.setLockTaskPackages(admin, policy.lockPackages(app.packageName).toTypedArray())
        dpm.setLockTaskFeatures(admin, features)
        for (pkg in policy.blocked) {
            if (inventory.exists(pkg) && !dpm.isApplicationHidden(admin, pkg)) check(dpm.setApplicationHidden(admin, pkg, true)) { "Cannot hide $pkg" }
        }
        for (pkg in policy.apps.map { it.packageName } + app.packageName) dpm.setUninstallBlocked(admin, pkg, true)
        dpm.setUserControlDisabledPackages(admin, listOf(app.packageName) +
            if (dns.localAvailable()) listOf(DnsFilter.LOCAL_PACKAGE) else emptyList())
        if (Build.VERSION.SDK_INT >= 33) check(dpm.setPermissionGrantState(admin, app.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)) {
            "Cannot enable supervised-maintenance notifications"
        }
        check(dpm.setPermittedAccessibilityServices(admin, emptyList())) { "Disable third-party accessibility services before activation" }
        restrictions.forEach { dpm.addUserRestriction(admin, it) }
        dns.configure(admin, policy.dnsHost)
    }

    fun verify(policy: DevicePolicy): List<String> = buildList {
        if (!isOwner) { add("Lucy is not Device Owner"); return@buildList }
        if (!app.getSystemService(KeyguardManager::class.java).isDeviceSecure) add("Device screen lock is not configured")
        val actual = dpm.getUserRestrictions(admin)
        restrictions.filterNot { actual.getBoolean(it) }.forEach { add("Missing restriction: $it") }
        if (dpm.getLockTaskPackages(admin).toSet() != policy.lockPackages(app.packageName)) add("Lock-task allowlist mismatch")
        if (dpm.getLockTaskFeatures(admin) != features) add("System UI policy mismatch")
        val handler = app.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        if (handler?.activityInfo?.packageName != app.packageName) add("Lucy is not the default HOME app")
        for (pkg in policy.blocked) if (inventory.exists(pkg) && !dpm.isApplicationHidden(admin, pkg)) add("Unapproved app visible: $pkg")
        for (pkg in policy.lockPackages(app.packageName)) {
            if (!inventory.exists(pkg)) add("Required package missing: $pkg")
            else if (dpm.isApplicationHidden(admin, pkg) || dpm.isPackageSuspended(admin, pkg)) add("Approved app unavailable: $pkg")
        }
        for (pkg in policy.support.keys) {
            if (!inventory.exists(pkg)) add("System dependency missing: $pkg")
            else if (dpm.isApplicationHidden(admin, pkg) || dpm.isPackageSuspended(admin, pkg)) add("System dependency unavailable: $pkg")
        }
        if (!dpm.isUninstallBlocked(admin, app.packageName)) add("DPC uninstall protection missing")
        if (app.packageName !in dpm.getUserControlDisabledPackages(admin)) add("DPC data/force-stop protection missing")
        if (dns.localAvailable() && (!dpm.isUninstallBlocked(admin, DnsFilter.LOCAL_PACKAGE) ||
            DnsFilter.LOCAL_PACKAGE !in dpm.getUserControlDisabledPackages(admin))) add("Local filter protection missing")
        if (Build.VERSION.SDK_INT >= 33 && dpm.getPermissionGrantState(admin, app.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            add("Maintenance notification permission missing")
        if (dpm.getPermittedAccessibilityServices(admin) != emptyList<String>()) add("Accessibility-service policy mismatch")
        if (policy.apps.any { !dpm.isUninstallBlocked(admin, it.packageName) }) add("App uninstall protection missing")
        if (!dns.configured(admin, policy.dnsHost)) add("DNS filtering policy mismatch")
    }

    @Synchronized fun reconcile(restoreMaintenance: Boolean = false) {
        if (internetWindow() != null && (restoreMaintenance || !temporaryInternetActive())) restoreTemporaryInternet()
        if (!store.configured()) return
        check(isOwner) { "Device Owner access was lost" }
        val hadMaintenance = store.maintenance() != null
        if (hadMaintenance) {
            if (!restoreMaintenance && inMaintenance()) return
            store.update { it.put("restoreRequired", true); it.remove("maintenance") }
            store.log("Daily restrictions restored after maintenance or process restart")
        }
        val stored = store.policy()
        val policy = currentPolicy()
        val installed = inventory.apps(policy).map { it.packageName }.toSet()
        val available = policy.pruningMissingOptionalApps(installed)
        val blocked = inventory.blockedFor(available)
        val normalized = available.copy(blocked = blocked)
        val missingHomeMigration = store.read().optJSONObject("effective")?.has("homePackages") != true
        val changed = stored == null || missingHomeMigration || normalized.copy(revision = stored.revision) != stored
        val candidate = if (changed) normalized.copy(revision = policy.revision + 1) else policy
        if (available.apps != policy.apps) {
            val removed = (policy.apps - available.apps.toSet()).joinToString { it.label }
            store.update { state ->
                state.optJSONObject("draft")?.let { draftJson ->
                    val draft = DevicePolicy.fromJson(draftJson).pruningMissingOptionalApps(installed)
                    state.put("draft", draft.toJson())
                }
            }
            store.log("Removed uninstalled optional apps from daily policy: $removed")
        }
        // Retrying a failed/pending application is intentional and must be read back before success.
        try { commit(candidate); store.update { it.remove("restoreRequired") } }
        finally {
            // Restoring policy alone does not re-enter lock task while Settings is foreground.
            // Device Owner may bring HOME forward; HOME enters lock task after user unlock.
            if ((hadMaintenance || restoreMaintenance) && app.getSystemService(UserManager::class.java).isUserUnlocked) {
                runCatching { app.startActivity(Intent(app, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                    .onFailure { store.log("Unable to return to managed HOME: ${it.message}") }
            }
        }
    }

    @Synchronized fun beginMaintenance(session: String, exitCurrentTask: () -> Unit = {}) {
        app.auth.requireSession(session)
        check(isOwner && store.configured()) { "Activate a managed policy first" }
        // An authenticated recovery session must remain possible when automatic restoration
        // needs an app reinstalled or another administrator intervention.
        check(canScheduleMaintenance()) { "Maintenance requires Alarms & reminders access" }
        val window = MaintenanceWindow(SystemClock.elapsedRealtime() + 30 * 60_000, bootCount())
        store.update { it.put("maintenance", JSONObject().put("deadline", window.deadline).put("boot", window.boot)) }
        try {
            app.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, window.deadline, expiryIntent())
            // A root activity can stop the whole session without destroying itself.
            // Run only once, after the lease prevents lifecycle callbacks re-locking.
            exitCurrentTask()
            // Remove every managed task, including the original root task from
            // earlier launches. Stopping only the current HOME activity leaves
            // that root locked and can repeatedly destroy/recreate HOME.
            // The persisted maintenance lease prevents HOME from re-entering;
            // apply() restores the daily allowlist when maintenance ends.
            dpm.setLockTaskPackages(admin, emptyArray())
            maintenanceRelaxed.forEach { dpm.clearUserRestriction(admin, it) }
            for (pkg in currentPolicy().blocked) if (inventory.exists(pkg)) dpm.setApplicationHidden(admin, pkg, false)
            for (pkg in currentPolicy().apps.filterNot { it.essential }.map { it.packageName }) dpm.setUninstallBlocked(admin, pkg, false)
            app.startForegroundService(Intent(app, MaintenanceService::class.java))
            store.log("Supervised maintenance started (30 minutes)")
        } catch (e: Exception) { reconcile(restoreMaintenance = true); throw e }
        app.changed()
    }
    private fun returnHome() {
        if (app.getSystemService(UserManager::class.java).isUserUnlocked) runCatching {
            app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { store.log("Unable to return to managed HOME") }
    }
    @Synchronized fun beginTemporaryInternet(session: String, confirmed: Boolean) {
        app.auth.requireSession(session)
        check(!dns.localAvailable()) { "Temporary unfiltered internet is unavailable with the local filter installed" }
        check(confirmed) { "Confirm the temporary loss of content filtering" }
        check(isOwner && (!store.configured() || inMaintenance())) { "Open supervised maintenance first" }
        check(canScheduleMaintenance()) { "Allow the maintenance timer before enabling temporary internet" }
        if (Build.VERSION.SDK_INT >= 33) check(dpm.setPermissionGrantState(admin, app.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)) {
            "Cannot show the supervised setup notification"
        }
        // Verify this device supports offline strict restoration BEFORE relaxing DNS.
        dns.enforceStrict(admin, DevicePolicy.DNS_HOST)
        val deadline = minOf(SystemClock.elapsedRealtime() + 15 * 60_000,
            store.maintenance()?.deadline ?: Long.MAX_VALUE)
        store.update { it.put("temporaryInternet", JSONObject().put("deadline", deadline).put("boot", bootCount())); it.remove("filter") }
        try {
            app.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, internetExpiryIntent())
            app.startForegroundService(Intent(app, MaintenanceService::class.java))
            dns.temporaryInternet(admin)
            store.log("Administrator explicitly enabled unfiltered setup internet (at most 15 minutes)")
        } catch (e: Exception) { restoreTemporaryInternet(); throw e }
        app.changed()
    }
    @Synchronized fun restoreTemporaryInternet() {
        if (internetWindow() == null) return
        // Keep the persisted restoration obligation until strict host/mode are read back.
        dns.enforceStrict(admin, DevicePolicy.DNS_HOST)
        store.update { it.remove("temporaryInternet"); it.remove("filter") }
        app.getSystemService(AlarmManager::class.java).cancel(internetExpiryIntent())
        store.log("Temporary internet ended; strict filtered DNS restored")
        app.changed()
    }
    @Synchronized fun expireSessions() {
        if (internetWindow() != null && !temporaryInternetActive()) restoreTemporaryInternet()
        if ((store.maintenance() != null && !inMaintenance()) || store.read().optBoolean("restoreRequired")) finishMaintenance()
    }
    @Synchronized fun prepareFiltering(session: String) {
        app.auth.requireSession(session)
        check(isOwner) { "Filtering requires Device Owner mode; launcher preview cannot enforce DNS" }
        restoreTemporaryInternet()
        dns.configure(admin, DevicePolicy.DNS_HOST)
        checkFilter()
    }
    @Synchronized fun finishMaintenance() {
        restoreTemporaryInternet()
        if (!store.configured()) return
        try { reconcile(restoreMaintenance = true) }
        finally {
            if (!hasTimedSession()) {
                app.getSystemService(AlarmManager::class.java).cancel(expiryIntent())
            }
            app.changed()
        }
    }
    @Synchronized fun checkFilter() {
        if (!isOwner || temporaryInternetActive()) return
        if (!dns.configured(admin, DevicePolicy.DNS_HOST)) {
            store.update { it.remove("filter") }; return
        }
        val result = dns.probe()
        store.update { it.put("filter", JSONObject().put("checkedAt", result.checkedAt).put("passed", result.passed)
            .put("detail", result.detail).put("controlPassed", result.controlPassed).put("network", result.network)
            .put("checks", JSONArray(result.checks.map { check -> JSONObject().put("label", check.label).put("passed", check.passed).put("detail", check.detail) }))) }
        app.changed()
    }
    fun report(error: Throwable) {
        runCatching { store.setErrors(listOf(error.message ?: "Policy operation failed")) }
        app.changed()
    }
    @Synchronized fun decommission(session: String, confirmation: String) {
        app.auth.requireSession(session)
        check(isOwner && inMaintenance()) { "Enter supervised maintenance first" }
        require(confirmation == "ERASE THIS DEVICE") { "Confirmation does not match" }
        store.log("Administrator confirmed device erase")
        @Suppress("DEPRECATION")
        dpm.wipeData(0)
    }
}
