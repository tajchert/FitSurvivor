package pl.tajchert.fitsurvivor;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResult;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import de.greenrobot.event.EventBus;
import me.panavtec.coordinator.Coordinator;
import me.panavtec.coordinator.qualifiers.Actions;
import me.panavtec.coordinator.qualifiers.CoordinatorComplete;
import me.tatarka.support.job.JobInfo;
import me.tatarka.support.job.JobParameters;
import me.tatarka.support.job.JobScheduler;
import me.tatarka.support.job.JobService;

public class FitJobBackground extends JobService {
    private static final String TAG = FitJobBackground.class.getSimpleName();
    private static final int JOB_ID = 42523;

    private SharedPreferences sharedPreferences;

    private GoogleApiClient mClient = null;
    private ConsecutiveDays consecutiveDays;
    private StepsToday stepsToday;
    private boolean showNotif = false;
    private JobParameters jobParameters;
    private long stepsDuringConsecutiveDays;

    //Coordinator lib stuff
    private static final int ACTION_STEPS_TODAY = 1;
    private static final int ACTION_CONSECUTIVE_DAYS = 2;
    @Actions({ACTION_STEPS_TODAY, ACTION_CONSECUTIVE_DAYS})
    Coordinator coordinator;

    public FitJobBackground() {}

    @Override
    public boolean onStartJob(JobParameters params) {
        jobParameters = params;
        sharedPreferences = FitJobBackground.this.getSharedPreferences("pl.tajchert.fitsurvivor", Context.MODE_PRIVATE);
        buildFitnessClient();
        mClient.connect();
        Coordinator.inject(this);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if(consecutiveDays == null || stepsToday == null || showNotif == false) {
            return true;
        }
        return false;
    }

    private void buildFitnessClient() {
        // Create the Google API Client
        mClient = new GoogleApiClient.Builder(this)
                .addApi(Fitness.HISTORY_API)
                .addScope(new Scope(Scopes.FITNESS_ACTIVITY_READ))
                .addScope(new Scope(Scopes.FITNESS_LOCATION_READ))
                .addConnectionCallbacks(
                        new GoogleApiClient.ConnectionCallbacks() {

                            @Override
                            public void onConnected(Bundle bundle) {
                                Log.i(TAG, "Connected!!!");
                                getStepsToday();
                            }

                            @Override
                            public void onConnectionSuspended(int i) {
                                // If your connection to the sensor gets lost at some point,
                                // you'll be able to determine the reason and react to it here.
                                if (i == GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST) {
                                    Log.i(TAG, "Connection lost.  Cause: Network Lost.");
                                } else if (i == GoogleApiClient.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED) {
                                    Log.i(TAG, "Connection lost.  Reason: Service Disconnected");
                                }
                                EventBus.getDefault().post(new FitError());
                            }
                        }
                )
                .addOnConnectionFailedListener(
                        new GoogleApiClient.OnConnectionFailedListener() {
                            @Override
                            public void onConnectionFailed(ConnectionResult result) {
                                EventBus.getDefault().post(new FitError());
                            }
                        }
                ).build();
    }

    private void getStepsToday() {
        Calendar cal = Calendar.getInstance();
        Date now = new Date();
        cal.setTime(now);
        final long endTime = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long startTime = cal.getTimeInMillis();

        final DataReadRequest readRequest = new DataReadRequest.Builder()
                .read(DataType.TYPE_STEP_COUNT_DELTA)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build();

        Fitness.HistoryApi.readData(mClient, readRequest).setResultCallback(new ResultCallback<DataReadResult>() {
            @Override
            public void onResult(DataReadResult dataReadResult) {
                DataSet stepData = dataReadResult.getDataSet(DataType.TYPE_STEP_COUNT_DELTA);
                int totalSteps = 0;
                for (DataPoint dp : stepData.getDataPoints()) {
                    for(Field field : dp.getDataType().getFields()) {
                        int steps = dp.getValue(field).asInt();
                        totalSteps += steps;
                    }
                }
                Log.d(TAG, "getStepsToday :" + totalSteps);
                stepsToday = new StepsToday(totalSteps);
                if(stepsToday.steps > 100) {
                    //100 to avoid miscounted steps
                    //Check if previous days were also so good.
                    long startTime = getMidnightTime();
                    checkStreak(startTime, 0);
                } else {
                    //We dont check it so assume it is done
                    coordinator.completeAction(ACTION_CONSECUTIVE_DAYS);
                }
                coordinator.completeAction(ACTION_STEPS_TODAY);
            }
        });
    }

    private long getMidnightTime() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        return cal.getTimeInMillis();
    }

    private void checkStreak(final long timeEnd, final int consecutiveDaysNumber) {
        if(consecutiveDaysNumber == 0) {
            stepsDuringConsecutiveDays = 0;
        }
        final long timeStart =  (timeEnd - TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS));

        final DataReadRequest readRequest = new DataReadRequest.Builder()
                .read(DataType.TYPE_STEP_COUNT_DELTA)
                .enableServerQueries()
                .setTimeRange(timeStart, timeEnd, TimeUnit.MILLISECONDS)
                .build();

        Fitness.HistoryApi.readData(mClient, readRequest).setResultCallback(new ResultCallback<DataReadResult>() {
            @Override
            public void onResult(DataReadResult dataReadResult) {
                DataSet stepData = dataReadResult.getDataSet(DataType.TYPE_STEP_COUNT_DELTA);
                int totalSteps = 0;
                for (DataPoint dp : stepData.getDataPoints()) {
                    for(Field field : dp.getDataType().getFields()) {
                        int steps = dp.getValue(field).asInt();
                        totalSteps += steps;
                    }
                }
                if(totalSteps > 0) {
                    stepsDuringConsecutiveDays += totalSteps;
                    checkStreak((timeEnd - TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)), consecutiveDaysNumber + 1);
                } else {
                    //set dayNumber as consecutiveDays
                    if(consecutiveDaysNumber > 0) {
                        Log.d(TAG, "onResult consecutive days: " + consecutiveDaysNumber);
                        consecutiveDays = new ConsecutiveDays(stepsDuringConsecutiveDays, consecutiveDaysNumber);
                        coordinator.completeAction(ACTION_CONSECUTIVE_DAYS);
                    }
                }
            }
        });
    }

    /**
     * Gets called when both actions (steps during today and consecutive days) were executed
     */
    @CoordinatorComplete public void allDone() {
        Log.d(TAG, "allDone actions are done!");
        boolean notifShown = false;//Used to avoid showing notification more than onece a day

        if(consecutiveDays != null) {
            EventBus.getDefault().post(consecutiveDays);
            if (consecutiveDays.days > 0) {
                if(shouldShowNotification()) {
                    notifShown = true;
                    showConsecutiveDaysAchievment(consecutiveDays);
                }
            }
        }

        if(stepsToday != null) {
            EventBus.getDefault().post(stepsToday);
            if (stepsToday.steps > 100) {
                //100 to avoid miscounted steps
                if(shouldShowNotification()) {
                    notifShown = true;
                    showChetAchievment(stepsToday);
                }
            }
        }
        if(notifShown) {
            saveNotificationTime();
        }

        jobFinished(jobParameters, false);
    }

    private void showChetAchievment(StepsToday stepsToday) {
        int notificationId = 32423;
        Intent viewIntent = new Intent(this, FitJobBackground.class);
        PendingIntent viewPendingIntent = PendingIntent.getActivity(this, 0, viewIntent, 0);

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.drawable.ic_trophy_black_48dp)
                        .setContentTitle("Chet\'s achievement unlocked!")
                        .setContentText("Good job!")
                        .setSubText("You are on your best way to survive another day!")
                        .setAutoCancel(true)
                        .setContentIntent(viewPendingIntent);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(notificationId, notificationBuilder.build());

    }

    private void showConsecutiveDaysAchievment(ConsecutiveDays consecutiveDays) {
        int notificationId = 32455;
        Intent viewIntent = new Intent(this, FitJobBackground.class);
        PendingIntent viewPendingIntent = PendingIntent.getActivity(this, 0, viewIntent, 0);

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.drawable.ic_trophy_black_48dp)
                        .setContentTitle("Movement spree!")
                        .setSubText("On average with " + consecutiveDays.stepsPerDay + " steps per day.")
                        .setContentText("This is your " + consecutiveDays.days + " day with movement.")
                        .setContentIntent(viewPendingIntent);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(notificationId, notificationBuilder.build());
    }

    public static void scheduleJob(Context context) {
        JobScheduler jobScheduler = JobScheduler.getInstance(context);
        jobScheduler.cancel(JOB_ID);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, FitJobBackground.class))
                .setPeriodic((TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS))/4)//as we need to check more often than once a day as first check can be with 0 steps and result in no notification
                .setPersisted(true)
                .setRequiresCharging(true)
                .build();
        jobScheduler.schedule(job);
    }

    public static void runJob(Context context) {
        JobScheduler jobScheduler = JobScheduler.getInstance(context);
        JobInfo job = new JobInfo.Builder(233222, new ComponentName(context, FitJobBackground.class)).setOverrideDeadline(1000).build();

        jobScheduler.schedule(job);
    }

    private boolean shouldShowNotification() {
        long prevTime = sharedPreferences.getLong("LastRefresh", 0);
        Calendar prevRefresh = Calendar.getInstance();
        prevRefresh.setTimeInMillis(prevTime);
        if(Calendar.getInstance().get(Calendar.DAY_OF_YEAR) != prevRefresh.get(Calendar.DAY_OF_YEAR) || prevTime == 0) {
            //it is another day, lets refresh
            return  true;
        } else {
            return false;
        }
    }

    private void saveNotificationTime() {
        sharedPreferences.edit().putLong("LastRefresh", System.currentTimeMillis()).apply();
    }

    public class FitError {}
}
