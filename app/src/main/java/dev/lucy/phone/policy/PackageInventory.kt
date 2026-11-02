@file:Suppress("DEPRECATION")
package dev.lucy.phone.policy

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import dev.lucy.phone.configuration.AppEntry
import dev.lucy.phone.configuration.DevicePolicy
import dev.lucy.phone.configuration.PolicyRules

class PackageInventory(private val context: Context) {
    private val pm = context.packageManager
    private val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
    fun apps(previous: DevicePolicy? = null): List<AppEntry> {
        val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), flags)
            .map { AppEntry(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString()) }
        val essential = essentialPackages()
        return (found + previous?.apps.orEmpty()).distinctBy { it.packageName }
            .filter { it.packageName != context.packageName && it.packageName != "dnsfilter.android" && exists(it.packageName) }
            .map { it.copy(essential = it.packageName in essential) }
            .sortedWith(compareByDescending<AppEntry> { it.essential }.thenBy { it.label.lowercase() })
    }
    private fun resolve(intent: Intent) = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let {
        if (it.packageName == "android") null else it.packageName
    }
    fun essentialPackages(): Set<String> = buildSet {
        context.getSystemService(TelecomManager::class.java).defaultDialerPackage?.let(::add)
        Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
        listOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
            Intent("android.intent.action.SHOW_ALARMS")).mapNotNull(::resolve).forEach(::add)
    }
    fun forbidden(): Set<String> = PolicyRules.forbiddenPackages(buildSet {
        listOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=dev.lucy.phone")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)).forEach { intent ->
            pm.queryIntentActivities(intent, flags).forEach { add(it.activityInfo.packageName) }
        }
    })
    fun initial(): DevicePolicy {
        val all = apps()
        val essential = essentialPackages()
        val order = listOf("Phone", "Messages", "Contacts", "Camera", "Photos", "Maps", "Slack", "ChatGPT", "Authenticator", "Calendar", "Clock")
        val visible = all.filter { it.packageName in essential }
            .sortedBy { order.indexOf(it.label).let { index -> if (index < 0) order.size else index } }
        val support = linkedMapOf(context.packageName to "Device owner and HOME").apply { putAll(criticalSupport()) }
        val activitySupport = mutableSetOf<String>()
        context.getSystemService(InputMethodManager::class.java).inputMethodList.forEach { support[it.packageName] = "Installed input method" }
        listOf("com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.android.phone", "com.android.server.telecom")
            .filter(::exists).forEach { support[it] = "System permissions and emergency telephony"; activitySupport += it }
        listOf(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")).mapNotNull(::resolve).forEach {
            support[it] = "System picker or emergency calling"; activitySupport += it
        }
        val policy = DevicePolicy(baseline = "${Build.MANUFACTURER}/${Build.MODEL} • Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) • ${Build.DISPLAY}",
            apps = visible, support = support, activitySupport = activitySupport - forbidden(), blocked = emptySet())
        return policy.copy(blocked = blockedFor(policy, all))
    }
    fun criticalSupport(): Map<String, String> = listOf("com.google.android.gms", "com.android.providers.contacts",
        "com.android.providers.telephony", "com.android.providers.media", "com.google.android.providers.media.module",
        "com.android.providers.downloads", "com.google.android.webview", "com.android.webview", "com.android.stk",
        "com.android.carrierdefaultapp", "com.google.android.cellbroadcastreceiver", "com.android.cellbroadcastreceiver",
        "com.google.android.apps.safetyhub", "dnsfilter.android").filter(::exists).associateWith { "Core services or local filtering dependency; not a daily launcher entry" }
    fun blockedFor(policy: DevicePolicy, all: List<AppEntry> = apps(policy)): Set<String> =
        (all.map { it.packageName }.toSet() + forbidden()) - policy.lockPackages(context.packageName) - policy.support.keys - "com.android.settings"
    fun exists(packageName: String) = runCatching {
        pm.getApplicationInfo(packageName, flags).flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED != 0
    }.getOrDefault(false)
    fun launchIntent(app: AppEntry) = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(app.packageName, app.activity)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
