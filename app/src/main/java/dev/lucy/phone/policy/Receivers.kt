package dev.lucy.phone.policy

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.PersistableBundle
import dev.lucy.phone.LucyApp
import dev.lucy.phone.launcher.HomeActivity

class LucyAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class ReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val restore = intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED)
        // Network validation may take longer than a BroadcastReceiver's execution allowance.
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(901, ComponentName(context, ReconcileJobService::class.java))
            .setMinimumLatency(500).setOverrideDeadline(3_000).setPersisted(true)
            .setExtras(PersistableBundle().apply { putBoolean("restore", restore) }).build())
    }
}

class MaintenanceExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startForegroundService(Intent(context, MaintenanceService::class.java))
    }
}

class ProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LucyApp
        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                val allowed = if (Build.VERSION.SDK_INT >= 31) intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES) else null
                if (allowed != null && DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE !in allowed) setResult(RESULT_CANCELED)
                else setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                if (!app.engine.isOwner) setResult(RESULT_CANCELED)
                else {
                    setResult(RESULT_OK)
                    startActivity(Intent(this, HomeActivity::class.java))
                }
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
