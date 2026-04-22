package com.bossscraper.app;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bossscraper.app.adapter.JobAdapter;
import com.bossscraper.app.network.BossApiClient;
import com.bossscraper.app.viewmodel.JobViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private JobViewModel viewModel;
    private JobAdapter adapter;

    private Spinner spinnerCity;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvLastUpdate, tvCountdown, tvJobCount;
    private View emptyView;
    private androidx.appcompat.widget.AppCompatButton btnRefresh;

    private String selectedCityCode = "100010000";
    private boolean isSpinnerInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initViewModel();
        setupCitySpinner();
        setupRecyclerView();
        setupSwipeRefresh();
        setupRefreshButton();

        // Initial load
        viewModel.fetchJobs(selectedCityCode, "全国");
    }

    private void initViews() {
        spinnerCity = findViewById(R.id.spinnerCity);
        recyclerView = findViewById(R.id.recyclerView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);
        tvLastUpdate = findViewById(R.id.tvLastUpdate);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvJobCount = findViewById(R.id.tvJobCount);
        emptyView = findViewById(R.id.emptyView);
        btnRefresh = findViewById(R.id.btnRefresh);
    }

    private void initViewModel() {
        viewModel = new ViewModelProvider(this).get(JobViewModel.class);

        viewModel.getFilteredJobs().observe(this, jobs -> {
            if (jobs != null && !jobs.isEmpty()) {
                adapter.setJobs(jobs);
                recyclerView.setVisibility(View.VISIBLE);
                emptyView.setVisibility(View.GONE);
                tvJobCount.setText(jobs.size() + " 条");
            } else {
                adapter.setJobs(null);
                recyclerView.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
                tvJobCount.setText("");
            }
            swipeRefresh.setRefreshing(false);
        });

        viewModel.getIsLoading().observe(this, loading -> {
            if (loading) {
                progressBar.setVisibility(View.VISIBLE);
                btnRefresh.setEnabled(false);
                btnRefresh.setText("加载中...");
            } else {
                progressBar.setVisibility(View.GONE);
                btnRefresh.setEnabled(true);
                btnRefresh.setText("立即刷新");
                swipeRefresh.setRefreshing(false);
            }
        });

        viewModel.getErrorMessage().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }
        });

        viewModel.getLastUpdateTime().observe(this, time ->
                tvLastUpdate.setText("最后更新：" + time));

        viewModel.getCountdownSeconds().observe(this, seconds -> {
            if (seconds != null) {
                int min = seconds / 60;
                int sec = seconds % 60;
                tvCountdown.setText(String.format(Locale.CHINA,
                        "下次刷新：%02d:%02d", min, sec));
            }
        });
    }

    private void setupCitySpinner() {
        String[] cityNames = new String[BossApiClient.CITY_CODES.length];
        for (int i = 0; i < BossApiClient.CITY_CODES.length; i++) {
            cityNames[i] = BossApiClient.CITY_CODES[i][0];
        }

        ArrayAdapter<String> cityAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, cityNames);
        cityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCity.setAdapter(cityAdapter);

        spinnerCity.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!isSpinnerInitialized) {
                    isSpinnerInitialized = true;
                    return;
                }
                String cityName = BossApiClient.CITY_CODES[pos][0];
                String cityCode = BossApiClient.CITY_CODES[pos][1];
                selectedCityCode = cityCode;
                viewModel.fetchJobs(cityCode, cityName);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupRecyclerView() {
        adapter = new JobAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        recyclerView.setHasFixedSize(false);
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.purple_700);
        swipeRefresh.setOnRefreshListener(() -> {
            int pos = spinnerCity.getSelectedItemPosition();
            if (pos >= 0 && pos < BossApiClient.CITY_CODES.length) {
                String cityName = BossApiClient.CITY_CODES[pos][0];
                String cityCode = BossApiClient.CITY_CODES[pos][1];
                viewModel.fetchJobs(cityCode, cityName);
            }
        });
    }

    private void setupRefreshButton() {
        btnRefresh.setOnClickListener(v -> {
            int pos = spinnerCity.getSelectedItemPosition();
            if (pos >= 0 && pos < BossApiClient.CITY_CODES.length) {
                String cityName = BossApiClient.CITY_CODES[pos][0];
                String cityCode = BossApiClient.CITY_CODES[pos][1];
                viewModel.fetchJobs(cityCode, cityName);
            }
        });
    }
}
