package com.bossscraper.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private Spinner    spinnerCity;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvLastUpdate, tvCountdown, tvJobCount;
    private TextView tvLoginState, btnLoginStatus;
    private TextView tvRealDataBanner, tvDemoBanner;
    private View emptyView;
    private androidx.appcompat.widget.AppCompatButton btnRefresh;

    private boolean spinnerReady = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        // ViewModel 必须在 initViews 之后初始化
        viewModel = new ViewModelProvider(this).get(JobViewModel.class);
        observeViewModel();
        setupCitySpinner();
        setupRecyclerView();
        setupSwipeRefresh();
        setupButtons();

        // 首次加载
        triggerRefresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 直接读 SharedPreferences，避免 ViewModel 未初始化时的竞争
        boolean loggedIn = getSharedPreferences("boss_prefs", MODE_PRIVATE)
                .getBoolean("logged_in", false);
        updateLoginStateUI(loggedIn);

        if (getIntent() != null && getIntent().getBooleanExtra("refresh_after_login", false)) {
            getIntent().removeExtra("refresh_after_login");
            handler.postDelayed(this::triggerRefresh, 400);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ── 初始化 ──────────────────────────────────────────────

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

    private void observeViewModel() {
        viewModel.getFilteredJobs().observe(this, jobs -> {
            if (jobs != null && !jobs.isEmpty()) {
                adapter.setJobs(jobs);
                recyclerView.setVisibility(View.VISIBLE);
                emptyView.setVisibility(View.GONE);
                tvJobCount.setText(jobs.size() + " 条");
            } else {
                recyclerView.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
                tvJobCount.setText("");
            }
            swipeRefresh.setRefreshing(false);
        });

        viewModel.getIsLoading().observe(this, loading -> {
            if (loading == null) return;
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            btnRefresh.setEnabled(!loading);
            btnRefresh.setText(loading ? "加载中..." : "立即刷新");
            if (!loading) swipeRefresh.setRefreshing(false);
        });

        viewModel.getErrorMessage().observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            if (msg.contains("登录已过期")) {
                updateLoginStateUI(false);
            }
        });

        viewModel.getLastUpdateTime().observe(this, time ->
                tvLastUpdate.setText("最后更新：" + (time != null ? time : "--")));

        viewModel.getCountdownSeconds().observe(this, seconds -> {
            if (seconds == null) return;
            int min = seconds / 60, sec = seconds % 60;
            tvCountdown.setText(
                    String.format(Locale.CHINA, "下次刷新：%02d:%02d", min, sec));
        });

        viewModel.getIsRealData().observe(this, realData -> {
            // 只在确认是真实数据时更新横幅，不能用 false 来强制"退出登录"状态
            if (Boolean.TRUE.equals(realData)) {
                updateLoginStateUI(true);
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
        cityAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
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
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.purple_700);
        swipeRefresh.setOnRefreshListener(this::triggerRefresh);
    }

    private void setupButtons() {
        btnRefresh.setOnClickListener(v -> triggerRefresh());

        btnLoginStatus.setOnClickListener(v -> {
            if (viewModel.isLoggedIn()) {
                new AlertDialog.Builder(this)
                        .setTitle("退出登录")
                        .setMessage("确定退出登录？退出后将显示演示数据。")
                        .setPositiveButton("退出", (d, w) -> {
                            viewModel.logout();
                            updateLoginStateUI(false);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                startActivity(new Intent(this, LoginActivity.class));
            }
        });

        tvDemoBanner.setOnClickListener(v ->
                startActivity(new Intent(this, LoginActivity.class)));
    }

    // ── 工具方法 ─────────────────────────────────────────────

    private void triggerRefresh() {
        if (viewModel == null) return;
        int pos = spinnerCity.getSelectedItemPosition();
        if (pos < 0 || pos >= BossApiClient.CITY_CODES.length) pos = 0;
        viewModel.fetchJobs(
                BossApiClient.CITY_CODES[pos][1],
                BossApiClient.CITY_CODES[pos][0]);
    }

    private void updateLoginStateUI(boolean loggedIn) {
        if (loggedIn) {
            btnLoginStatus.setText("退出登录");
            tvLoginState.setText("已登录 · 获取真实数据");
            tvRealDataBanner.setVisibility(View.VISIBLE);
            tvDemoBanner.setVisibility(View.GONE);
        } else {
            btnLoginStatus.setText("扫码登录");
            tvLoginState.setText("未登录 · 显示演示数据");
            tvRealDataBanner.setVisibility(View.GONE);
            tvDemoBanner.setVisibility(View.VISIBLE);
        }
    }
}
