package com.bossscraper.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    private static final String BOSS_LOGIN_URL = "https://www.zhipin.com/web/user/?ka=header-login";
    private static final String BOSS_HOME_URL  = "zhipin.com";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        webView     = findViewById(R.id.webView);
        progressBar = findViewById(R.id.loginProgress);
        tvHint      = findViewById(R.id.tvLoginHint);

        setupWebView();
        webView.loadUrl(BOSS_LOGIN_URL);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
        );

        // 同步 WebView Cookie 到 CookieManager
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);

                // 检测是否已登录（跳转到主页说明登录成功）
                if (url != null && isLoggedIn(url)) {
                    extractAndSaveCookie(url);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebResourceRequest request) {
                // 所有链接都在 WebView 内打开
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * 判断是否已登录：登录成功后 Boss 会跳转回主页或个人中心
     */
    private boolean isLoggedIn(String url) {
        return (url.contains(BOSS_HOME_URL) && !url.contains("/web/user/"))
                || url.contains("/web/geek/")
                || url.equals("https://www.zhipin.com/");
    }

    private void extractAndSaveCookie(String url) {
        CookieManager cm = CookieManager.getInstance();
        // 确保 Cookie 已同步
        cm.flush();

        String cookies = cm.getCookie("https://www.zhipin.com");
        if (cookies == null || cookies.isEmpty()) {
            cookies = cm.getCookie(url);
        }

        if (cookies != null && cookies.contains("wt2")) {
            // 保存 Cookie 到 SharedPreferences
            SharedPreferences prefs = getSharedPreferences("boss_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                 .putString("cookie", cookies)
                 .putBoolean("logged_in", true)
                 .putLong("login_time", System.currentTimeMillis())
                 .apply();

            Toast.makeText(this, "登录成功！正在获取真实招聘数据...", Toast.LENGTH_SHORT).show();

            // 返回主界面并刷新
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("refresh", true);
            startActivity(intent);
            finish();
        } else {
            // Cookie 里还没有 wt2，说明登录流程未完成，继续等待
            tvHint.setText("请完成扫码或账号密码登录...");
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
