package com.bossscraper.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private JobViewModel viewModel;
    private JobAdapter   adapter;

    // Declare all as View — avoids ClassCastException regardless of XML widget type
    private Spinner            spinnerCity;
    private RecyclerView       recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar        progressBar;
    private View               emptyView;
    private TextView           tvLastUpdate;
    private TextView           tvCountdown;
    private TextView           tvJobCount;
    private TextView           tvLoginState;
    private View               btnLoginStatus;
    private View               tvRealDataBanner;
    private View               tvDemoBanner;
    private View               btnRefresh;

    private boolean spinnerReady = false;
    private boolean wasLoggedIn  = false;

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        showPreviousCrashIfAny();   // display last crash log to user

        initViews();
        setupRecyclerView();
        setupSpinner();
        setupSwipeRefresh();
        setupClickListeners();

        viewModel = new ViewModelProvider(this).get(JobViewModel.class);
        observe();

        wasLoggedIn = isLoggedIn();
        updateLoginUI(wasLoggedIn);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean loggedIn = isLoggedIn();
        updateLoginUI(loggedIn);
        if (loggedIn != wasLoggedIn) {   // login state changed -> re-fetch
            wasLoggedIn = loggedIn;
            refresh();
        }
    }

    // ── Views ────────────────────────────────────────────────────────────

    private void initViews() {
        spinnerCity      = findViewById(R.id.spinnerCity);
        recyclerView     = findViewById(R.id.recyclerView);
        swipeRefresh     = findViewById(R.id.swipeRefresh);
        progressBar      = findViewById(R.id.progressBar);
        emptyView        = findViewById(R.id.emptyView);
        tvLastUpdate     = findViewById(R.id.tvLastUpdate);
        tvCountdown      = findViewById(R.id.tvCountdown);
        tvJobCount       = findViewById(R.id.tvJobCount);
        tvLoginState     = findViewById(R.id.tvLoginState);
        btnLoginStatus   = findViewById(R.id.btnLoginStatus);
        tvRealDataBanner = findViewById(R.id.tvRealDataBanner);
        tvDemoBanner     = findViewById(R.id.tvDemoBanner);
        btnRefresh       = findViewById(R.id.btnRefresh);
    }

    private void setupRecyclerView() {
        adapter = new JobAdapter(this);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }
    }

    private void setupSpinner() {
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
        if (swipeRefresh != null) swipeRefresh.setOnRefreshListener(this::refresh);
    }

    private void setupClickListeners() {
        if (btnRefresh != null) btnRefresh.setOnClickListener(v -> refresh());

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

    private void observe() {
        viewModel.getFilteredJobs().observe(this, jobs -> {
            boolean has = jobs != null && !jobs.isEmpty();
            adapter.setJobs(has ? jobs : null);
            if (recyclerView != null) recyclerView.setVisibility(has ? View.VISIBLE : View.GONE);
            if (emptyView    != null) emptyView.setVisibility(has ? View.GONE : View.VISIBLE);
            if (tvJobCount   != null) ((TextView) tvJobCount).setText(has ? jobs.size() + " 条" : "");
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        });

        viewModel.getIsLoading().observe(this, loading -> {
            if (loading == null) return;
            if (progressBar  != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (!loading && swipeRefresh != null) swipeRefresh.setRefreshing(false);
        });

        viewModel.getErrorMessage().observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });

        viewModel.getLastUpdateTime().observe(this, t -> {
            if (tvLastUpdate != null)
                ((TextView) tvLastUpdate).setText("最后更新：" + (t != null ? t : "--"));
        });

        viewModel.getCountdownSeconds().observe(this, s -> {
            if (tvCountdown == null || s == null) return;
            ((TextView) tvCountdown).setText(
                    String.format(Locale.CHINA, "下次刷新：%02d:%02d", s / 60, s % 60));
        });

        viewModel.getIsRealData().observe(this, real -> {
            // Only update banner when we know for sure
            if (real == null) return;
            if (tvRealDataBanner != null) tvRealDataBanner.setVisibility(real ? View.VISIBLE : View.GONE);
            if (tvDemoBanner     != null) tvDemoBanner.setVisibility(real ? View.GONE : View.VISIBLE);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean isLoggedIn() {
        return getSharedPreferences("boss_prefs", MODE_PRIVATE)
                .getBoolean("logged_in", false);
    }

    private void refresh() {
        if (viewModel == null || spinnerCity == null) return;
        int pos = spinnerCity.getSelectedItemPosition();
        if (pos < 0 || pos >= BossApiClient.CITY_CODES.length) pos = 0;
        viewModel.fetchJobs(BossApiClient.CITY_CODES[pos][1],
                            BossApiClient.CITY_CODES[pos][0]);
    }

    private void doLogout() {
        getSharedPreferences("boss_prefs", MODE_PRIVATE).edit().clear().apply();
        wasLoggedIn = false;
        if (viewModel != null) viewModel.logout();
        updateLoginUI(false);
    }

    private void updateLoginUI(boolean loggedIn) {
        if (btnLoginStatus instanceof TextView)
            ((TextView) btnLoginStatus).setText(loggedIn ? "退出登录" : "登录");
        if (tvLoginState != null)
            ((TextView) tvLoginState).setText(loggedIn
                    ? "已登录 · 获取真实数据" : "未登录 · 显示数据");
        // Banner visibility is driven by isRealData observer, not login flag
    }

    private void showPreviousCrashIfAny() {
        try {
            File f = new File(getFilesDir(), "crash.txt");
            if (!f.exists()) return;
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            f.delete();
            String log = sb.toString().trim();
            if (log.isEmpty()) return;
            String display = log.length() > 1500
                    ? log.substring(0, 1500) + "\n...(截断)" : log;
            new AlertDialog.Builder(this)
                    .setTitle("上次崩溃日志（请截图）")
                    .setMessage(display)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showCrash: " + e);
        }
    }
}
