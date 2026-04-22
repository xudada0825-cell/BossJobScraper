package com.bossscraper.app.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.bossscraper.app.model.JobItem;
import com.bossscraper.app.network.BossApiClient;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

public class JobViewModel extends AndroidViewModel {

    private static final String TAG              = "JobViewModel";
    private static final int    REFRESH_INTERVAL = 180; // 3 minutes

    // Hardcoded: Guangzhou only
    private static final String CITY_CODE = "763";
    private static final String CITY_NAME = "广州";

    private final MutableLiveData<List<JobItem>> filteredJobs   = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean>       isLoading      = new MutableLiveData<>(false);
    private final MutableLiveData<String>        errorMessage   = new MutableLiveData<>();
    private final MutableLiveData<String>        lastUpdateTime = new MutableLiveData<>("--");
    private final MutableLiveData<Integer>       countdownSecs  = new MutableLiveData<>(REFRESH_INTERVAL);
    private final MutableLiveData<Boolean>       isRealData     = new MutableLiveData<>(null);

    private final BossApiClient api;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger cdAtom      = new AtomicInteger(REFRESH_INTERVAL);

    private boolean fetching = false;

    private Timer refreshTimer;
    private Timer countdownTimer;

    public JobViewModel(@NonNull Application app) {
        super(app);
        api = new BossApiClient(app);
        startTimers();
    }

    public LiveData<List<JobItem>> getFilteredJobs()    { return filteredJobs; }
    public LiveData<Boolean>       getIsLoading()       { return isLoading; }
    public LiveData<String>        getErrorMessage()    { return errorMessage; }
    public LiveData<String>        getLastUpdateTime()  { return lastUpdateTime; }
    public LiveData<Integer>       getCountdownSeconds(){ return countdownSecs; }
    public LiveData<Boolean>       getIsRealData()      { return isRealData; }

    public void fetchJobs() {
        if (fetching) return;
        fetching = true;
        isLoading.postValue(true);
        errorMessage.postValue(null);

        api.fetchForeignTradeJobs(CITY_CODE, new BossApiClient.FetchCallback() {
            @Override
            public void onSuccess(List<JobItem> jobs, boolean real) {
                fetching = false;
                filteredJobs.postValue(jobs);
                isLoading.postValue(false);
                isRealData.postValue(real);
                lastUpdateTime.postValue(
                        new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()));
                cdAtom.set(REFRESH_INTERVAL);
                countdownSecs.postValue(REFRESH_INTERVAL);
                Log.d(TAG, "fetchJobs success: " + jobs.size() + " jobs");
            }

            @Override
            public void onError(String msg) {
                fetching = false;
                Log.e(TAG, "fetchJobs error: " + msg);
                isLoading.postValue(false);
                errorMessage.postValue(msg);
                cdAtom.set(REFRESH_INTERVAL);
                countdownSecs.postValue(REFRESH_INTERVAL);
            }
        });
    }

    private void startTimers() {
        stopTimers();

        refreshTimer = new Timer("AutoRefresh", true);
        refreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                mainHandler.post(() -> fetchJobs());
            }
        }, REFRESH_INTERVAL * 1000L, REFRESH_INTERVAL * 1000L);

        countdownTimer = new Timer("Countdown", true);
        countdownTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                int v = cdAtom.decrementAndGet();
                if (v < 0) { cdAtom.set(0); v = 0; }
                countdownSecs.postValue(v);
            }
        }, 1000L, 1000L);
    }

    private void stopTimers() {
        if (refreshTimer   != null) { refreshTimer.cancel();   refreshTimer   = null; }
        if (countdownTimer != null) { countdownTimer.cancel(); countdownTimer = null; }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        stopTimers();
        api.destroy();
    }
}
