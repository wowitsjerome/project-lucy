package dev.lucy.phone.policy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.lucy.phone.LucyApp
import dev.lucy.phone.R
import dev.lucy.phone.launcher.HomeActivity

class MaintenanceService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val app = application as LucyApp
            app.executor.execute {
                runCatching { app.engine.expireSessions() }.onFailure(app.engine::report)
                handler.post {
                    // Only the already-foreground service may stop itself. Stopping it from the
                    // policy worker before onCreate runs causes Android's FGS-start timeout crash.
                    if (app.engine.hasTimedSession()) handler.postDelayed(this, 1_000) else stopSelf()
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        showNotification()
        handler.post(tick)
    }
    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("maintenance", "Supervised maintenance", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1, Notification.Builder(this, "maintenance").setSmallIcon(R.drawable.ic_lucy)
            .setContentTitle("Lucy · supervised setup").setContentText("Keep administrator custody. Temporary internet expires within 15 minutes; maintenance within 30 minutes. Return to Lucy for status.")
            .setOngoing(true).setContentIntent(intent).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A new startForegroundService can reuse an existing service after a rapid stop/start.
        // Satisfy each foreground start, not only the first onCreate.
        showNotification()
        return START_STICKY
    }
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
