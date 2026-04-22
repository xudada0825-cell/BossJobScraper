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
    private static final String BOSS_HOME      = "zhipin.com";

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

        findViewById(R.id.btnBack).setOnClickListener(v -> goBackOrFinish());

        setupWebView();
        webView.loadUrl(BOSS_LOGIN_URL);
    }

    private void goBackOrFinish() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
        );

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                if (url != null && detectLogin(url)) {
                    extractAndSaveCookie(url);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
            }
        });
    }

    private boolean detectLogin(String url) {
        return (url.contains(BOSS_HOME) && !url.contains("/web/user/"))
                || url.contains("/web/geek/")
                || url.equals("https://www.zhipin.com/");
    }

    private void extractAndSaveCookie(String url) {
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        String cookies = cm.getCookie("https://www.zhipin.com");
        if (cookies == null || cookies.isEmpty()) cookies = cm.getCookie(url);

        if (cookies != null && cookies.contains("wt2")) {
            SharedPreferences prefs = getSharedPreferences("boss_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                 .putString("cookie", cookies)
                 .putBoolean("logged_in", true)
                 .putLong("login_time", System.currentTimeMillis())
                 .apply();

            Toast.makeText(this, "登录成功！正在获取真实招聘数据...", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("refresh", true);
            startActivity(intent);
            finish();
        } else {
            tvHint.setText("请完成扫码或账号密码登录...");
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
