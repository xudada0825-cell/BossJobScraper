package com.bossscraper.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bossscraper.app.adapter.JobAdapter;
import com.bossscraper.app.network.BossApiClient;
import com.bossscraper.app.viewmodel.JobViewModel;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private JobViewModel viewModel;
    private JobAdapter   adapter;

    // All views typed exactly as they appear in layout XML
    private Spinner          spinnerCity;
    private RecyclerView     recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar      progressBar;
    private TextView         tvLastUpdate;
    private TextView         tvCountdown;
    private TextView         tvJobCount;
    private TextView         tvLoginState;
    private TextView         btnLoginStatus;   // TextView in layout
    private TextView         tvRealDataBanner;
    private TextView         tvDemoBanner;
    private View             emptyView;
    private TextView         btnRefresh;       // use TextView for refresh too (safer)

    private boolean spinnerReady = false;
    private boolean returnedFromLogin = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        viewModel = new ViewModelProvider(this).get(JobViewModel.class);
        observeViewModel();
        setupCitySpinner();
        setupRecyclerView();
        setupSwipeRefresh();
        setupButtons();

        // First load
        if (savedInstanceState == null) {
            triggerRefresh();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean loggedIn = getSharedPreferences("boss_prefs", MODE_PRIVATE)
                .getBoolean("logged_in", false);
        updateLoginStateUI(loggedIn);

        // If we just came back from login, do one refresh
        if (returnedFromLogin) {
            returnedFromLogin = false;
            triggerRefresh();
        }
    }

    // ── Views ────────────────────────────────────────────────────────────

    private void initViews() {
        spinnerCity      = findViewById(R.id.spinnerCity);
        recyclerView     = findViewById(R.id.recyclerView);
        swipeRefresh     = findViewById(R.id.swipeRefresh);
        progressBar      = findViewById(R.id.progressBar);
        tvLastUpdate     = findViewById(R.id.tvLastUpdate);
        tvCountdown      = findViewById(R.id.tvCountdown);
        tvJobCount       = findViewById(R.id.tvJobCount);
        tvLoginState     = findViewById(R.id.tvLoginState);
        btnLoginStatus   = findViewById(R.id.btnLoginStatus);
        tvRealDataBanner = findViewById(R.id.tvRealDataBanner);
        tvDemoBanner     = findViewById(R.id.tvDemoBanner);
        emptyView        = findViewById(R.id.emptyView);
        // btnRefresh is AppCompatButton in layout — find as View first, set click listener
        View refreshBtn  = findViewById(R.id.btnRefresh);
        if (refreshBtn != null) refreshBtn.setOnClickListener(v -> triggerRefresh());
    }

    // ── Observers ────────────────────────────────────────────────────────

    private void observeViewModel() {
        viewModel.getFilteredJobs().observe(this, jobs -> {
            if (jobs != null && !jobs.isEmpty()) {
                adapter.setJobs(jobs);
                if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
                if (emptyView    != null) emptyView.setVisibility(View.GONE);
                if (tvJobCount   != null) tvJobCount.setText(jobs.size() + " 条");
            } else {
                adapter.setJobs(null);
                if (recyclerView != null) recyclerView.setVisibility(View.GONE);
                if (emptyView    != null) emptyView.setVisibility(View.VISIBLE);
                if (tvJobCount   != null) tvJobCount.setText("");
            }
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        });

        viewModel.getIsLoading().observe(this, loading -> {
            if (loading == null) return;
            if (progressBar  != null)
                progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (!loading && swipeRefresh != null)
                swipeRefresh.setRefreshing(false);
        });

        viewModel.getErrorMessage().observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            if (msg.contains("登录已过期")) {
                getSharedPreferences("boss_prefs", MODE_PRIVATE)
                        .edit().putBoolean("logged_in", false).apply();
                updateLoginStateUI(false);
            }
        });

        viewModel.getLastUpdateTime().observe(this, time -> {
            if (tvLastUpdate != null)
                tvLastUpdate.setText("最后更新：" + (time != null ? time : "--"));
        });

        viewModel.getCountdownSeconds().observe(this, seconds -> {
            if (tvCountdown == null || seconds == null) return;
            int min = seconds / 60, sec = seconds % 60;
            tvCountdown.setText(
                    String.format(Locale.CHINA, "下次刷新：%02d:%02d", min, sec));
        });

        viewModel.getIsRealData().observe(this, realData -> {
            if (Boolean.TRUE.equals(realData)) {
                updateLoginStateUI(true);
            }
        });
    }

    // ── Setup ────────────────────────────────────────────────────────────

    private void setupCitySpinner() {
        if (spinnerCity == null) return;
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
                if (!spinnerReady) { spinnerReady = true; return; }
                viewModel.fetchJobs(
                        BossApiClient.CITY_CODES[pos][1],
                        BossApiClient.CITY_CODES[pos][0]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupRecyclerView() {
        adapter = new JobAdapter(this);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }
    }

    private void setupSwipeRefresh() {
        if (swipeRefresh == null) return;
        swipeRefresh.setOnRefreshListener(this::triggerRefresh);
    }

    private void setupButtons() {
        if (btnLoginStatus != null) {
            btnLoginStatus.setOnClickListener(v -> {
                if (viewModel.isLoggedIn()) {
                    new AlertDialog.Builder(this)
                            .setTitle("退出登录")
                            .setMessage("确定退出登录？退出后将显示演示数据。")
                            .setPositiveButton("退出", (d, w) -> {
                                viewModel.logout();
                                getSharedPreferences("boss_prefs", MODE_PRIVATE)
                                        .edit().putBoolean("logged_in", false).apply();
                                updateLoginStateUI(false);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                } else {
                    returnedFromLogin = true;
                    startActivity(new Intent(this, LoginActivity.class));
                }
            });
        }

        if (tvDemoBanner != null) {
            tvDemoBanner.setOnClickListener(v -> {
                returnedFromLogin = true;
                startActivity(new Intent(this, LoginActivity.class));
            });
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void triggerRefresh() {
        if (viewModel == null || spinnerCity == null) return;
        int pos = spinnerCity.getSelectedItemPosition();
        if (pos < 0 || pos >= BossApiClient.CITY_CODES.length) pos = 0;
        viewModel.fetchJobs(
                BossApiClient.CITY_CODES[pos][1],
                BossApiClient.CITY_CODES[pos][0]);
    }

    private void updateLoginStateUI(boolean loggedIn) {
        if (btnLoginStatus != null)
            btnLoginStatus.setText(loggedIn ? "退出登录" : "登录");
        if (tvLoginState != null)
            tvLoginState.setText(loggedIn ? "已登录 · 获取真实数据" : "未登录 · 显示演示数据");
        if (tvRealDataBanner != null)
            tvRealDataBanner.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        if (tvDemoBanner != null)
            tvDemoBanner.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
    }
}
