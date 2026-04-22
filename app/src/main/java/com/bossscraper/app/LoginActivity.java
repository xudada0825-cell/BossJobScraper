package com.bossscraper.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
    private TextView tvHint;

    // 用 volatile 保证多线程可见性
    private volatile boolean finished = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (finished) return;
            if (!checkCookie()) {
                handler.postDelayed(this, 2000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        webView     = findViewById(R.id.webView);
        progressBar = findViewById(R.id.loginProgress);
        tvHint      = findViewById(R.id.tvLoginHint);
        View btnDone = findViewById(R.id.btnDone);
        View btnBack = findViewById(R.id.btnBack);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                if (webView != null && webView.canGoBack()) webView.goBack();
                else finish();
            });
        }
        if (btnDone != null) {
            btnDone.setOnClickListener(v -> {
                if (!checkCookie()) {
                    Toast.makeText(this,
                            "还未检测到登录，请先完成登录再点此按钮",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

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
            public void onPageStarted(WebView view, String url,
                    android.graphics.Bitmap favicon) {
                if (finished) return;
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (finished) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                // 跳到首页说明登录完成，延迟检测
                if (url != null && (
                        url.equals("https://www.zhipin.com/") ||
                        url.equals("https://www.zhipin.com") ||
                        url.startsWith("https://www.zhipin.com/web/geek/") ||
                        url.startsWith("https://www.zhipin.com/web/boss/"))) {
                    handler.postDelayed(LoginActivity.this::checkCookie, 800);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (finished || progressBar == null) return;
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    /**
     * 检查 cookie 是否包含 wt2，成功则保存并跳转主页。
     * @return true 表示已登录并跳转
     */
    private boolean checkCookie() {
        if (finished) return false;
        try {
            CookieManager.getInstance().flush();
            String cookie = CookieManager.getInstance()
                    .getCookie("https://www.zhipin.com");
            Log.d(TAG, "cookie check: " +
                    (cookie != null ? cookie.length() + "chars" : "null"));

            if (cookie != null && cookie.contains("wt2")) {
                finished = true;
                handler.removeCallbacksAndMessages(null);

                // 保存登录状态
                getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("cookie", cookie)
                    .putBoolean("logged_in", true)
                    .putLong("login_time", System.currentTimeMillis())
                    .apply();

                Toast.makeText(this, "登录成功！", Toast.LENGTH_SHORT).show();

                // 先销毁 WebView 再跳转，避免内存泄漏和回调问题
                destroyWebView();

                // 直接用 startActivity 跳主页，不传 extra
                android.content.Intent intent =
                        new android.content.Intent(this, MainActivity.class);
                // FLAG_ACTIVITY_CLEAR_TOP：如果 MainActivity 已存在则复用
                intent.setFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                              | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "checkCookie error: " + e);
        }
        return false;
    }

    private void destroyWebView() {
        if (webView == null) return;
        try {
            // 先从父 View 移除，再销毁——这是防止 WebView 内存泄漏的标准做法
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.clearHistory();
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        } catch (Exception e) {
            Log.e(TAG, "destroyWebView: " + e);
        }
    }

    @Override
    protected void onDestroy() {
        finished = true;
        handler.removeCallbacksAndMessages(null);
        destroyWebView();
        super.onDestroy();
    }
}
