package com.bossscraper.app;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bossscraper.app.adapter.JobAdapter;
import com.bossscraper.app.viewmodel.JobViewModel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private JobViewModel viewModel;
    private JobAdapter   adapter;

    private RecyclerView       recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar        progressBar;
    private View               emptyView;
    private TextView           tvLastUpdate;
    private TextView           tvCountdown;
    private TextView           tvJobCount;
    private TextView           tvStatus;
    private TextView           btnRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        showPreviousCrashIfAny();

        recyclerView = findViewById(R.id.recyclerView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar  = findViewById(R.id.progressBar);
        emptyView    = findViewById(R.id.emptyView);
        tvLastUpdate = findViewById(R.id.tvLastUpdate);
        tvCountdown  = findViewById(R.id.tvCountdown);
        tvJobCount   = findViewById(R.id.tvJobCount);
        tvStatus     = findViewById(R.id.tvStatus);
        btnRefresh   = findViewById(R.id.btnRefresh);

        adapter = new JobAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::refresh);
        if (btnRefresh != null) btnRefresh.setOnClickListener(v -> refresh());

        viewModel = new ViewModelProvider(this).get(JobViewModel.class);

        viewModel.getFilteredJobs().observe(this, jobs -> {
            boolean has = jobs != null && !jobs.isEmpty();
            adapter.setJobs(has ? jobs : null);
            recyclerView.setVisibility(has ? View.VISIBLE : View.GONE);
            emptyView.setVisibility(has ? View.GONE : View.VISIBLE);
            if (tvJobCount != null)
                tvJobCount.setText(has ? "共 " + jobs.size() + " 条" : "");
            swipeRefresh.setRefreshing(false);
        });

        viewModel.getIsLoading().observe(this, loading -> {
            if (loading == null) return;
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (!loading) swipeRefresh.setRefreshing(false);
        });

        viewModel.getErrorMessage().observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            if (tvStatus != null) tvStatus.setText("⚠ " + msg);
        });

        viewModel.getLastUpdateTime().observe(this, t -> {
            if (tvLastUpdate != null)
                tvLastUpdate.setText("更新：" + (t != null ? t : "--"));
        });

        viewModel.getCountdownSeconds().observe(this, s -> {
            if (tvCountdown == null || s == null) return;
            tvCountdown.setText(String.format(Locale.CHINA,
                    "刷新：%02d:%02d", s / 60, s % 60));
        });

        viewModel.getIsRealData().observe(this, real -> {
            if (real == null || tvStatus == null) return;
            tvStatus.setText(real ? "● 数据来源：智联招聘" : "○ 暂无数据");
        });

        refresh();
    }

    private void refresh() {
        if (viewModel == null) return;
        viewModel.fetchJobs();
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
