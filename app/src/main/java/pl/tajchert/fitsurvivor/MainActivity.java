package pl.tajchert.fitsurvivor;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.CardView;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.fitness.Fitness;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import de.greenrobot.event.EventBus;


/**
 * This sample demonstrates how to use the Sensors API of the Google Fit platform to find
 * available data sources and to register/unregister listeners to those sources. It also
 * demonstrates how to authenticate a user with Google Play Services.
 */
public class MainActivity extends AppCompatActivity implements SwipeRefreshLayout.OnRefreshListener {
    private SharedPreferences sharedPreferences;
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int REQUEST_OAUTH = 1;
    @InjectView(R.id.cardviewOnboarding)
    CardView cardviewOnboarding;

    @InjectView(R.id.cardviewDaily)
    CardView cardviewDaily;

    @InjectView(R.id.cardviewSpree)
    CardView cardviewSpree;

    @InjectView(R.id.cardviewError)
    CardView cardviewError;

    @InjectView(R.id.cardviewNoActivity)
    CardView cardviewNoActivity;

    @InjectView(R.id.imageError)
    ImageView imageError;

    @InjectView(R.id.textErrorContent)
    TextView textErrorContent;

    @InjectView(R.id.textDailyDesc)
    TextView textDailyDesc;

    @InjectView(R.id.textSpreeDesc)
    TextView textSpreeDesc;

    @InjectView(R.id.swipeRefresh)
    SwipeRefreshLayout swipeRefreshLayout;

    /**
     *  Track whether an authorization activity is stacking over the current activity, i.e. when
     *  a known auth error is being resolved, such as showing the account chooser or presenting a
     *  consent dialog. This avoids common duplications as might happen on screen rotations, etc.
     */
    private long currentStepNumber;
    private static final String KEY_AUTH_PENDING = "auth_state_pending";

    private boolean authInProgress = false;

    private GoogleApiClient mClient = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.inject(this);
        EventBus.getDefault().register(this);
        sharedPreferences = getSharedPreferences("pl.tajchert.fitsurvivor", Context.MODE_PRIVATE);
        if(sharedPreferences.getBoolean("gotit", false)) {
            cardviewOnboarding.setVisibility(View.GONE);
        }

        if (savedInstanceState != null) {
            authInProgress = savedInstanceState.getBoolean(KEY_AUTH_PENDING);
        }

        swipeRefreshLayout.setColorSchemeColors(Color.parseColor("#FFF46E5F"), Color.parseColor("#F44336"), Color.parseColor("#D32F2F"));
        swipeRefreshLayout.setOnRefreshListener(this);
        buildFitnessClient();
        if(mClient != null && !mClient.isConnected() && !mClient.isConnecting()) {
            mClient.connect();
            //To fix problem with setRefreshing before onMeasure
            swipeRefreshLayout.post(new Runnable() {
                @Override
                public void run() {
                    swipeRefreshLayout.setRefreshing(true);
                }
            });
        }
    }

    /**
     * Used just for checking if we have permission and if not to show Activity to grant access to Fit data
     */
    private void buildFitnessClient() {
        // Create the Google API Client
        mClient = new GoogleApiClient.Builder(this)
                .addApi(Fitness.HISTORY_API)
                .addApi(Fitness.SENSORS_API)
                .addScope(new Scope(Scopes.FITNESS_ACTIVITY_READ))
                .addScope(new Scope(Scopes.FITNESS_LOCATION_READ))
                .addConnectionCallbacks(
                        new GoogleApiClient.ConnectionCallbacks() {

                            @Override
                            public void onConnected(Bundle bundle) {
                                Log.i(TAG, "Connected!!!");
                                swipeRefreshLayout.setRefreshing(true);
                                FitJobBackground.runJob(MainActivity.this);
                                FitJobBackground.scheduleJob(MainActivity.this);
                                mClient.disconnect();
                            }

                            @Override
                            public void onConnectionSuspended(int i) {
                                // If your connection to the sensor gets lost at some point,
                                // you'll be able to determine the reason and react to it here.
                                if (i == ConnectionCallbacks.CAUSE_NETWORK_LOST) {
                                    Log.i(TAG, "Connection lost.  Cause: Network Lost.");
                                } else if (i == ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED) {
                                    Log.i(TAG, "Connection lost.  Reason: Service Disconnected");
                                }
                                swipeRefreshLayout.setRefreshing(false);
                            }
                        }
                )
                .addOnConnectionFailedListener(
                        new GoogleApiClient.OnConnectionFailedListener() {
                            // Called whenever the API client fails to connect.
                            @Override
                            public void onConnectionFailed(ConnectionResult result) {
                                Log.i(TAG, "Connection failed. Cause: " + result.toString());
                                swipeRefreshLayout.setRefreshing(false);
                                if (!result.hasResolution()) {
                                    // Show the localized error dialog
                                    GooglePlayServicesUtil.getErrorDialog(result.getErrorCode(),
                                            MainActivity.this, 0).show();
                                    return;
                                }
                                // The failure has a resolution. Resolve it.
                                // Called typically when the app is not yet authorized, and an
                                // authorization dialog is displayed to the user.
                                if (!authInProgress) {
                                    try {
                                        Log.i(TAG, "Attempting to resolve failed connection");
                                        authInProgress = true;
                                        result.startResolutionForResult(MainActivity.this,
                                                REQUEST_OAUTH);
                                    } catch (IntentSender.SendIntentException e) {
                                        Log.e(TAG,
                                                "Exception while starting resolution activity", e);
                                    }
                                }
                            }
                        }
                ).build();
    }


    public void onEvent(ConsecutiveDays consecutiveDays) {
        Log.d(TAG, "onEvent consecutiveDays: " + consecutiveDays.days + ", steps: " + consecutiveDays.stepsPerDay);
        swipeRefreshLayout.setRefreshing(false);
        if(consecutiveDays.days == 0) {
            return;
        }
        cardviewError.setVisibility(View.GONE);
        cardviewNoActivity.setVisibility(View.GONE);
        cardviewSpree.setVisibility(View.VISIBLE);
        textSpreeDesc.setText("Awesome! You are on a movement spree! This is your " + consecutiveDays.days + " consecutive day with a movement.\n\nOn average you have done " + consecutiveDays.stepsPerDay + " steps per day, with a total of " + consecutiveDays.stepsTotal + " steps.");
    }

    public void onEvent(StepsToday stepsToday) {
        Log.d(TAG, "onEvent stepsToday: " + stepsToday.steps);
        swipeRefreshLayout.setRefreshing(false);
        if(stepsToday.steps < 100) {
            cardviewNoActivity.setVisibility(View.VISIBLE);
            cardviewDaily.setVisibility(View.GONE);
            cardviewError.setVisibility(View.GONE);
            cardviewSpree.setVisibility(View.GONE);
        } else {
            cardviewError.setVisibility(View.GONE);
            cardviewNoActivity.setVisibility(View.GONE);
            cardviewDaily.setVisibility(View.VISIBLE);
            textDailyDesc.setText("Congrats! You are on you best way to survive another day, steps so far : " + stepsToday.steps + ".\n\nKeep on stepping!");
        }
    }

    public void onEvent(FitJobBackground.FitError fitError) {
        Log.d(TAG, "onEvent fitError!");
        swipeRefreshLayout.setRefreshing(false);
        cardviewError.setVisibility(View.VISIBLE);
        cardviewSpree.setVisibility(View.GONE);
        cardviewDaily.setVisibility(View.GONE);
        cardviewNoActivity.setVisibility(View.GONE);
        textErrorContent.setText("Something went wrong. Make sure that you have Google Fit app on you phone and you authorized Fit Survivor to read its data.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_OAUTH) {
            authInProgress = false;
            if (resultCode == RESULT_OK) {
                // Make sure the app is not already connected or attempting to connect
                if (!mClient.isConnecting() && !mClient.isConnected()) {
                    mClient.connect();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_AUTH_PENDING, authInProgress);
    }

    @OnClick(R.id.buttonGotIt)
    public void gotIt() {
        //Save
        sharedPreferences.edit().putBoolean("gotit", true).apply();
        cardviewOnboarding.setVisibility(View.GONE);
    }

    @OnClick(R.id.buttonShareDaily)
    public void shareDaily() {
        //Share
        if(textDailyDesc.getText().toString() != null) {
            shareText("Chet\'s achievement unlocked! "+textDailyDesc.getText().toString());
        }
    }

    @OnClick(R.id.buttonShareSpree)
    public void shareSpree() {
        //Share
        if(textSpreeDesc.getText().toString() != null) {
            shareText(textSpreeDesc.getText().toString());
        }
    }

    private void shareText(String text) {
        text = "Fit Survivor\n\n" + text;
        Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
        sharingIntent.setType("text/plain");
        sharingIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "Fit Survivor");
        sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(sharingIntent,"Share using: "));
    }

    @Override
    public void onRefresh() {
        if(mClient != null && !mClient.isConnected() && !mClient.isConnecting()) {
            cardviewDaily.setVisibility(View.GONE);
            cardviewSpree.setVisibility(View.GONE);
            cardviewError.setVisibility(View.GONE);
            cardviewNoActivity.setVisibility(View.GONE);
            mClient.connect();
        }
    }
}