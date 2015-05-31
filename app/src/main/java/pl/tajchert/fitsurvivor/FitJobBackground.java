package pl.tajchert.fitsurvivor;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
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

    private GoogleApiClient mClient = null;
    private ConsecutiveDays consecutiveDays;
    private StepsToday stepsToday;
    private boolean showNotif = false;

    //Coordinator lib stuff
    private static final int ACTION_STEPS_TODAY = 1;
    private static final int ACTION_CONSECUTIVE_DAYS = 2;
    @Actions({ACTION_STEPS_TODAY, ACTION_CONSECUTIVE_DAYS})
    Coordinator coordinator;

    public FitJobBackground() {
    }

    @Override
    public boolean onStartJob(JobParameters params) {
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
                                Calendar cal = Calendar.getInstance();
                                cal.set(Calendar.HOUR_OF_DAY, 0);
                                cal.set(Calendar.MINUTE, 0);
                                cal.set(Calendar.SECOND, 0);
                                long startTime = cal.getTimeInMillis();
                                checkStreak(startTime, 0);
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
                EventBus.getDefault().post(stepsToday);
                coordinator.completeAction(ACTION_STEPS_TODAY);
            }
        });
    }

    private void checkStreak(final long timeEnd, final int consecutiveDaysNumber) {
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
                    checkStreak((timeEnd - TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)), consecutiveDaysNumber + 1);
                } else {
                    //set dayNumber as consecutiveDays
                    if(consecutiveDaysNumber > 0) {
                        Log.d(TAG, "onResult consecutive days: " + consecutiveDaysNumber);
                        consecutiveDays = new ConsecutiveDays(totalSteps, consecutiveDaysNumber);
                        EventBus.getDefault().post(consecutiveDays);
                        coordinator.completeAction(ACTION_CONSECUTIVE_DAYS);
                    }
                }
            }
        });
    }

    @CoordinatorComplete public void showNotification() {
        Log.d(TAG, "showNotification actions are done!");
        if(consecutiveDays == null) {
            Log.d(TAG, "showNotification consecutiveDays is null");
        }
        if(stepsToday == null) {
            Log.d(TAG, "showNotification stepsToday is null");
        }
        showNotif = true;
    }

    public static void scheduleJob(Context context) {
        JobScheduler jobScheduler = JobScheduler.getInstance(context);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, FitJobBackground.class))
                .setPeriodic(TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS))
                .setPersisted(true)
                .setRequiresCharging(true)
                .build();
        jobScheduler.schedule(job);
    }

    public class FitError {

    }
}
