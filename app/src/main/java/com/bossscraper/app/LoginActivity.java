package com.bossscraper.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    // PC 版扫码登录页（手机 UA 下会显示二维码）
    private static final String BOSS_QRCODE_URL =
            "https://www.zhipin.com/web/user/login?loginType=qrcode";
    // 登录成功后 Boss 跳转到这些路径
    private static final String HOME_URL = "https://www.zhipin.com/";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvHint;

    // 防止 extractAndSaveCookie 重复触发
    private boolean loginHandled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        webView     = findViewById(R.id.webView);
        progressBar = findViewById(R.id.loginProgress);
        tvHint      = findViewById(R.id.tvLoginHint);

        findViewById(R.id.btnBack).setOnClickListener(v -> goBackOrFinish());

        setupWebView();
        webView.loadUrl(BOSS_QRCODE_URL);
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
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 使用桌面 UA，让 Boss 展示二维码版登录
        s.setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
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
                if (url == null || loginHandled) return;

                // 只有跳转到首页或个人中心才认为登录成功
                boolean isHome = url.equals(HOME_URL)
                        || url.equals("https://www.zhipin.com")
                        || url.startsWith("https://www.zhipin.com/web/geek/");
                if (isHome) {
                    // 延迟 800ms 等 Cookie 落盘
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> extractAndSaveCookie(url), 800);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 全部在 WebView 内打开，不跳外部浏览器
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void extractAndSaveCookie(String url) {
        if (loginHandled) return;

        CookieManager cm = CookieManager.getInstance();
        cm.flush();

        String cookies = cm.getCookie("https://www.zhipin.com");
        if (cookies == null || cookies.isEmpty()) {
            cookies = cm.getCookie(url);
        }

        if (cookies != null && cookies.contains("wt2")) {
            loginHandled = true;

            SharedPreferences prefs =
                    getSharedPreferences("boss_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                 .putString("cookie", cookies)
                 .putBoolean("logged_in", true)
                 .putLong("login_time", System.currentTimeMillis())
                 .apply();

            Toast.makeText(this, "登录成功！正在获取真实招聘数据...",
                    Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                          | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("refresh_after_login", true);
            startActivity(intent);
            finish();
        } else {
            // Cookie 还没 wt2，可能还在登录中，继续等
            tvHint.setText("正在验证登录状态，请稍候...");
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
