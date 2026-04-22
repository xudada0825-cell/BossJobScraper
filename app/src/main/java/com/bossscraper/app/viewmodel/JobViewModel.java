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

    private static final String TAG = "JobViewModel";
    private static final int AUTO_REFRESH_SECONDS = 300; // 5 minutes

    private final MutableLiveData<List<JobItem>> filteredJobs    = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean>       isLoading       = new MutableLiveData<>(false);
    private final MutableLiveData<String>        errorMessage    = new MutableLiveData<>();
    private final MutableLiveData<String>        lastUpdateTime  = new MutableLiveData<>("--");
    private final MutableLiveData<Integer>       countdownSeconds = new MutableLiveData<>(AUTO_REFRESH_SECONDS);
    private final MutableLiveData<Boolean>       isRealData      = new MutableLiveData<>(false);

    private final BossApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Use AtomicInteger for countdown so Timer thread never calls LiveData.getValue()
    private final AtomicInteger countdownAtom = new AtomicInteger(AUTO_REFRESH_SECONDS);

    private String currentCityCode = "100010000";
    private String currentCityName = "全国";

    private Timer autoRefreshTimer;
    private Timer countdownTimer;

    public JobViewModel(@NonNull Application application) {
        super(application);
        apiClient = new BossApiClient(application);
        startTimers();
    }

    // ── Public API ───────────────────────────────────────────────────────

    public LiveData<List<JobItem>> getFilteredJobs()    { return filteredJobs; }
    public LiveData<Boolean>       getIsLoading()        { return isLoading; }
    public LiveData<String>        getErrorMessage()     { return errorMessage; }
    public LiveData<String>        getLastUpdateTime()   { return lastUpdateTime; }
    public LiveData<Integer>       getCountdownSeconds() { return countdownSeconds; }
    public LiveData<Boolean>       getIsRealData()       { return isRealData; }
    public boolean                 isLoggedIn()          { return apiClient.isLoggedIn(); }

    public void fetchJobs(String cityCode, String cityName) {
        currentCityCode = cityCode;
        currentCityName = cityName;

        // postValue is safe from any thread
        isLoading.postValue(true);
        errorMessage.postValue(null);

        apiClient.fetchForeignTradeJobs(cityCode, new BossApiClient.FetchCallback() {
            @Override
            public void onSuccess(List<JobItem> jobs, boolean realData) {
                Log.d(TAG, "Fetched " + jobs.size() + " jobs, real=" + realData);
                filteredJobs.postValue(jobs);
                isLoading.postValue(false);
                isRealData.postValue(realData);
                lastUpdateTime.postValue(getCurrentTimeStr());
                resetCountdown();
            }

            @Override
            public void onError(String errorMsg) {
                Log.e(TAG, "Fetch error: " + errorMsg);
                isLoading.postValue(false);
                errorMessage.postValue(errorMsg);
                resetCountdown();
            }
        });
    }

    public void logout() {
        apiClient.logout();
        isRealData.postValue(false);
        fetchJobs(currentCityCode, currentCityName);
    }

    // ── Timers ───────────────────────────────────────────────────────────

    private void startTimers() {
        stopTimers();

        // Auto-refresh every 5 minutes
        autoRefreshTimer = new Timer("AutoRefresh", true);
        autoRefreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // Post to main thread — fetchJobs itself is thread-safe via postValue
                mainHandler.post(() -> fetchJobs(currentCityCode, currentCityName));
            }
        }, AUTO_REFRESH_SECONDS * 1000L, AUTO_REFRESH_SECONDS * 1000L);

        // Countdown ticker — uses AtomicInteger, never touches LiveData.getValue()
        countdownTimer = new Timer("Countdown", true);
        countdownTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                int remaining = countdownAtom.decrementAndGet();
                if (remaining < 0) {
                    countdownAtom.set(0);
                    remaining = 0;
                }
                // postValue is safe from any thread
                countdownSeconds.postValue(remaining);
            }
        }, 1000, 1000);
    }

    private void resetCountdown() {
        countdownAtom.set(AUTO_REFRESH_SECONDS);
        countdownSeconds.postValue(AUTO_REFRESH_SECONDS);
    }

    private void stopTimers() {
        if (autoRefreshTimer != null) { autoRefreshTimer.cancel(); autoRefreshTimer = null; }
        if (countdownTimer   != null) { countdownTimer.cancel();   countdownTimer   = null; }
    }

    private String getCurrentTimeStr() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        stopTimers();
    }
}
