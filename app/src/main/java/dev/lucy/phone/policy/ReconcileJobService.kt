package dev.lucy.phone.policy

import android.app.job.JobParameters
import android.app.job.JobService
import dev.lucy.phone.LucyApp

class ReconcileJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as LucyApp
        app.executor.execute {
            try { app.engine.reconcile(restoreMaintenance = params.extras.getBoolean("restore")) }
            catch (e: Exception) { app.engine.report(e) }
            finally { app.changed(); jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
