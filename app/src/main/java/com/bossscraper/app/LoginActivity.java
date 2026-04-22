package com.bossscraper.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";
    private static final String LOGIN_URL = "https://www.zhipin.com/web/user/?ka=header-login";

    private WebView webView;
    private ProgressBar progressBar;
    private Button btnDone;
    private TextView tvHint;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 自动检测登录状态（页面跳转后自动完成，无需用户点按钮）
    private boolean loginHandled = false;
    private final Runnable cookiePoller = new Runnable() {
        @Override
        public void run() {
            if (loginHandled || isFinishing()) return;
            if (trySaveCookie(false)) return; // 静默检测，成功后自动跳转
            mainHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        webView     = findViewById(R.id.webView);
        progressBar = findViewById(R.id.loginProgress);
        btnDone     = findViewById(R.id.btnDone);
        tvHint      = findViewById(R.id.tvLoginHint);

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
            else finish();
        });

        // 用户手动点击"我已登录"作为兜底
        btnDone.setOnClickListener(v -> trySaveCookie(true));

        setupWebView();
        webView.loadUrl(LOGIN_URL);

        // 3 秒后开始轮询
        mainHandler.postDelayed(cookiePoller, 3000);
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
        // PC UA：Boss 直聘 PC 端提供账号/验证码/二维码三种登录方式
        s.setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
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
                Log.d(TAG, "pageFinish: " + url);

                // 页面跳转到首页/个人中心 = 登录成功，尝试自动提取
                if (!loginHandled && isHomeUrl(url)) {
                    mainHandler.postDelayed(() -> trySaveCookie(false), 800);
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
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private boolean isHomeUrl(String url) {
        if (url == null) return false;
        return url.equals("https://www.zhipin.com/")
            || url.equals("https://www.zhipin.com")
            || url.startsWith("https://www.zhipin.com/web/geek/")
            || url.startsWith("https://www.zhipin.com/web/boss/");
    }

    /**
     * 尝试提取并保存 Cookie。
     * @param showToast 为 true 时（用户手动点击）会显示失败 Toast
     * @return true = 登录成功并已跳转
     */
    private boolean trySaveCookie(boolean showToast) {
        if (loginHandled) return true;

        CookieManager.getInstance().flush();
        String cookies = CookieManager.getInstance().getCookie("https://www.zhipin.com");
        Log.d(TAG, "cookie check: " + (cookies != null ? cookies.length() + "chars" : "null"));

        if (cookies != null && cookies.contains("wt2")) {
            loginHandled = true;
            mainHandler.removeCallbacks(cookiePoller);

            getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("cookie", cookies)
                .putBoolean("logged_in", true)
                .putLong("login_time", System.currentTimeMillis())
                .apply();

            Toast.makeText(this, "登录成功！", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("refresh_after_login", true);
            startActivity(intent);
            finish();
            return true;
        } else {
            if (showToast) {
                Toast.makeText(this,
                    "未检测到登录状态，请先完成登录再点击此按钮",
                    Toast.LENGTH_LONG).show();
            }
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(cookiePoller);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
