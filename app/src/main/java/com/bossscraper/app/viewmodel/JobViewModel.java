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

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class JobViewModel extends AndroidViewModel {

    private static final String TAG = "JobViewModel";
    private static final long AUTO_REFRESH_INTERVAL = 5 * 60 * 1000L; // 5 minutes

    private final MutableLiveData<List<JobItem>> allJobs = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<JobItem>> filteredJobs = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<String> lastUpdateTime = new MutableLiveData<>("--");
    private final MutableLiveData<Integer> countdownSeconds = new MutableLiveData<>(300);

    private final BossApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String currentCityCode = "100010000"; // 全国
    private String currentCityFilter = "全国";
    private Timer autoRefreshTimer;
    private Timer countdownTimer;

    public JobViewModel(@NonNull Application application) {
        super(application);
        apiClient = new BossApiClient();
        startAutoRefresh();
    }

    public LiveData<List<JobItem>> getFilteredJobs() { return filteredJobs; }
    public LiveData<Boolean> getIsLoading() { return isLoading; }
    public LiveData<String> getErrorMessage() { return errorMessage; }
    public LiveData<String> getLastUpdateTime() { return lastUpdateTime; }
    public LiveData<Integer> getCountdownSeconds() { return countdownSeconds; }

    public void fetchJobs(String cityCode, String cityName) {
        this.currentCityCode = cityCode;
        this.currentCityFilter = cityName;

        isLoading.postValue(true);
        errorMessage.postValue(null);

        apiClient.fetchForeignTradeJobs(cityCode, new BossApiClient.FetchCallback() {
            @Override
            public void onSuccess(List<JobItem> jobs) {
                Log.d(TAG, "Fetched " + jobs.size() + " jobs");
                allJobs.postValue(jobs);
                filteredJobs.postValue(jobs);
                isLoading.postValue(false);
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

    private void startAutoRefresh() {
        stopAutoRefresh();
        autoRefreshTimer = new Timer("AutoRefresh", true);
        autoRefreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "Auto-refresh triggered");
                mainHandler.post(() -> fetchJobs(currentCityCode, currentCityFilter));
            }
        }, AUTO_REFRESH_INTERVAL, AUTO_REFRESH_INTERVAL);

        startCountdownTimer();
    }

    private void startCountdownTimer() {
        if (countdownTimer != null) {
            countdownTimer.cancel();
        }
        countdownTimer = new Timer("Countdown", true);
        countdownTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Integer current = countdownSeconds.getValue();
                if (current != null && current > 0) {
                    countdownSeconds.postValue(current - 1);
                }
            }
        }, 1000, 1000);
    }

    private void resetCountdown() {
        countdownSeconds.postValue(300);
    }

    private void stopAutoRefresh() {
        if (autoRefreshTimer != null) {
            autoRefreshTimer.cancel();
            autoRefreshTimer = null;
        }
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
    }

    private String getCurrentTimeStr() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "HH:mm:ss", java.util.Locale.CHINA);
        return sdf.format(new java.util.Date());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        stopAutoRefresh();
    }
}
