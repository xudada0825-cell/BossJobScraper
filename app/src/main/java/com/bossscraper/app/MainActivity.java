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

    // All views — typed as View or the exact XML type to avoid ClassCastException
    private Spinner            spinnerCity;
    private RecyclerView       recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar        progressBar;
    private TextView           tvLastUpdate;
    private TextView           tvCountdown;
    private TextView           tvJobCount;
    private TextView           tvLoginState;
    private View               btnLoginStatus;   // TextView in XML, use View
    private TextView           tvRealDataBanner;
    private TextView           tvDemoBanner;
    private View               emptyView;
    private View               btnRefresh;       // TextView in XML, use View

    // Track login state across onResume to detect login/logout transitions
    private boolean wasLoggedIn = false;
    private boolean spinnerReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupRecyclerView();
        setupCitySpinner();
        setupSwipeRefresh();
        setupClickListeners();

        viewModel = new ViewModelProvider(this).get(JobViewModel.class);
        observeViewModel();

        wasLoggedIn = isLoggedIn();
        updateLoginUI(wasLoggedIn);

        // Initial load
        triggerRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean loggedIn = isLoggedIn();
        updateLoginUI(loggedIn);

        // If login state changed (just logged in or logged out), refresh data
        if (loggedIn != wasLoggedIn) {
            wasLoggedIn = loggedIn;
            triggerRefresh();
        }
    }

    // ── Init ─────────────────────────────────────────────────────────────

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
        btnRefresh       = findViewById(R.id.btnRefresh);
    }

    private void setupRecyclerView() {
        adapter = new JobAdapter(this);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }
    }

    private void setupCitySpinner() {
        if (spinnerCity == null) return;
        String[] names = new String[BossApiClient.CITY_CODES.length];
        for (int i = 0; i < names.length; i++) names[i] = BossApiClient.CITY_CODES[i][0];

        ArrayAdapter<String> a = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCity.setAdapter(a);

        spinnerCity.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!spinnerReady) { spinnerReady = true; return; }
                if (viewModel != null)
                    viewModel.fetchJobs(BossApiClient.CITY_CODES[pos][1],
                                        BossApiClient.CITY_CODES[pos][0]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void setupSwipeRefresh() {
        if (swipeRefresh != null)
            swipeRefresh.setOnRefreshListener(this::triggerRefresh);
    }

    private void setupClickListeners() {
        if (btnRefresh != null)
            btnRefresh.setOnClickListener(v -> triggerRefresh());

        if (btnLoginStatus != null) {
            btnLoginStatus.setOnClickListener(v -> {
                if (isLoggedIn()) {
                    new AlertDialog.Builder(this)
                            .setTitle("退出登录")
                            .setMessage("确定退出登录？")
                            .setPositiveButton("退出", (d, w) -> doLogout())
                            .setNegativeButton("取消", null)
                            .show();
                } else {
                    startActivity(new Intent(this, LoginActivity.class));
                }
            });
        }

        if (tvDemoBanner != null)
            tvDemoBanner.setOnClickListener(v ->
                    startActivity(new Intent(this, LoginActivity.class)));
    }

    // ── Observers ────────────────────────────────────────────────────────

    private void observeViewModel() {
        viewModel.getFilteredJobs().observe(this, jobs -> {
            boolean hasJobs = jobs != null && !jobs.isEmpty();
            adapter.setJobs(hasJobs ? jobs : null);
            if (recyclerView != null)
                recyclerView.setVisibility(hasJobs ? View.VISIBLE : View.GONE);
            if (emptyView != null)
                emptyView.setVisibility(hasJobs ? View.GONE : View.VISIBLE);
            if (tvJobCount != null)
                tvJobCount.setText(hasJobs ? jobs.size() + " 条" : "");
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        });

        viewModel.getIsLoading().observe(this, loading -> {
            if (loading == null) return;
            if (progressBar != null)
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
                wasLoggedIn = false;
                updateLoginUI(false);
            }
        });

        viewModel.getLastUpdateTime().observe(this, t -> {
            if (tvLastUpdate != null)
                tvLastUpdate.setText("最后更新：" + (t != null ? t : "--"));
        });

        viewModel.getCountdownSeconds().observe(this, s -> {
            if (tvCountdown == null || s == null) return;
            tvCountdown.setText(String.format(Locale.CHINA,
                    "下次刷新：%02d:%02d", s / 60, s % 60));
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean isLoggedIn() {
        return getSharedPreferences("boss_prefs", MODE_PRIVATE)
                .getBoolean("logged_in", false);
    }

    private void triggerRefresh() {
        if (viewModel == null || spinnerCity == null) return;
        int pos = spinnerCity.getSelectedItemPosition();
        if (pos < 0 || pos >= BossApiClient.CITY_CODES.length) pos = 0;
        viewModel.fetchJobs(BossApiClient.CITY_CODES[pos][1],
                            BossApiClient.CITY_CODES[pos][0]);
    }

    private void doLogout() {
        getSharedPreferences("boss_prefs", MODE_PRIVATE)
                .edit().clear().apply();
        wasLoggedIn = false;
        if (viewModel != null) viewModel.logout();
        updateLoginUI(false);
    }

    private void updateLoginUI(boolean loggedIn) {
        // btnLoginStatus is a TextView in XML — cast safely
        if (btnLoginStatus instanceof TextView)
            ((TextView) btnLoginStatus).setText(loggedIn ? "退出登录" : "登录");
        if (tvLoginState != null)
            tvLoginState.setText(loggedIn
                    ? "已登录 · 获取真实数据" : "未登录 · 显示演示数据");
        if (tvRealDataBanner != null)
            tvRealDataBanner.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        if (tvDemoBanner != null)
            tvDemoBanner.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
    }
}
