package local.boox.notesdrive;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Persisted network-constrained work survives process death and device reboot. */
public final class AutoSyncJob extends JobService {
    private static final int IMMEDIATE = 7101, PERIODIC = 7102;
    static void schedule(Context context, boolean periodic) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!context.getSharedPreferences("drive", 0).getBoolean("automatic", false)) {
            scheduler.cancel(IMMEDIATE);
            scheduler.cancel(PERIODIC);
            return;
        }
        int id = periodic ? PERIODIC : IMMEDIATE;
        // Repeated saves must not postpone an already queued job.
        JobInfo pending = scheduler.getPendingJob(id);
        if (pending != null && (periodic || android.os.Build.VERSION.SDK_INT < 31 || pending.isExpedited())) return;
        JobInfo.Builder builder = new JobInfo.Builder(id, new ComponentName(context, AutoSyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (periodic) builder.setPeriodic(15 * 60 * 1000L);
        else if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (scheduler.schedule(builder.setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS) return;
            builder.setExpedited(false);
            builder.setMinimumLatency(1500).setOverrideDeadline(30000);
        } else builder.setMinimumLatency(1500).setOverrideDeadline(30000);
        scheduler.schedule(builder.build());
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        ((DriveSession) getApplication()).automaticSync(retry -> jobFinished(parameters, retry));
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) { return true; }
}
