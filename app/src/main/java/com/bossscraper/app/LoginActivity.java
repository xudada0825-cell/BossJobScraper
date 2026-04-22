package com.bossscraper.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
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
    private static final String LOGIN_URL =
            "https://www.zhipin.com/web/user/?ka=header-login";

    private WebView webView;
    private ProgressBar progressBar;
    private Button btnDone;
    private TextView tvHint;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            checkLogin(false);
            handler.postDelayed(this, 2000);
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

        if (webView == null || progressBar == null || btnDone == null || tvHint == null) {
            Log.e(TAG, "Layout view not found, finishing");
            finish();
            return;
        }

        btnDone.setOnClickListener(v -> checkLogin(true));

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
            else finish();
        });

        setupWebView();
        webView.loadUrl(LOGIN_URL);

        handler.postDelayed(poller, 5000);
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
            public void onPageFinished(WebView view, String url) {
                if (destroyed) return;
                progressBar.setVisibility(View.GONE);
                Log.d(TAG, "pageFinish: " + url);
                // 跳到首页 = 登录成功
                if (url != null && (
                        url.equals("https://www.zhipin.com/") ||
                        url.equals("https://www.zhipin.com") ||
                        url.startsWith("https://www.zhipin.com/web/geek/") ||
                        url.startsWith("https://www.zhipin.com/web/boss/"))) {
                    handler.postDelayed(() -> checkLogin(false), 1000);
                }
            }

            @Override
            public void onPageStarted(android.webkit.WebView view, String url,
                                      android.graphics.Bitmap favicon) {
                if (destroyed) return;
                progressBar.setVisibility(View.VISIBLE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (destroyed) return;
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    /**
     * @param showError 是否在失败时显示 Toast（用户手动点击时传 true）
     */
    private void checkLogin(boolean showError) {
        if (destroyed) return;

        try {
            CookieManager.getInstance().flush();
            String cookie = CookieManager.getInstance().getCookie("https://www.zhipin.com");
            Log.d(TAG, "cookie=" + (cookie != null ? cookie.length() + "chars" : "null"));

            if (cookie != null && cookie.contains("wt2")) {
                // 登录成功，先停止一切后台操作
                handler.removeCallbacksAndMessages(null);

                // 保存 cookie
                SharedPreferences prefs =
                        getSharedPreferences("boss_prefs", Context.MODE_PRIVATE);
                prefs.edit()
                     .putString("cookie", cookie)
                     .putBoolean("logged_in", true)
                     .putLong("login_time", System.currentTimeMillis())
                     .apply();

                Toast.makeText(this, "登录成功！", Toast.LENGTH_SHORT).show();

                // 回到主页，不传 extra，避免主页触发额外刷新逻辑
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                              | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();

            } else if (showError) {
                Toast.makeText(this,
                        "还未检测到登录，请先完成登录再点此按钮",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "checkLogin error: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
