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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    // PC 版登录页（会显示扫码/账密两个选项卡，JS 会自动点击扫码）
    private static final String LOGIN_URL =
            "https://www.zhipin.com/web/user/?ka=header-login";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvHint;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean loginHandled = false;

    // 每 2 秒轮询一次 cookie，只要拿到 wt2 就认为登录成功
    private final Runnable cookiePoller = new Runnable() {
        @Override
        public void run() {
            if (loginHandled || isFinishing()) return;
            checkCookieAndProceed();
            mainHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        webView     = findViewById(R.id.webView);
        progressBar = findViewById(R.id.loginProgress);
        tvHint      = findViewById(R.id.tvLoginHint);

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
            else finish();
        });

        setupWebView();
        webView.loadUrl(LOGIN_URL);

        // 延迟 5 秒后开始轮询（留时间给页面加载）
        mainHandler.postDelayed(cookiePoller, 5000);
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // PC UA，让 Boss 直聘显示扫码选项（手机 UA 只有验证码登录）
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
                Log.d(TAG, "pageStart: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                Log.d(TAG, "pageFinish: " + url);
                if (loginHandled) return;

                // 注入 JS：自动点击「扫码登录」tab（如果存在的话）
                String js =
                    "(function(){" +
                    "  var tabs = document.querySelectorAll('.qr-code-box,.scan-login,li[data-type=\"qrcode\"],.login-tab');" +
                    "  for(var i=0;i<tabs.length;i++){" +
                    "    var t=tabs[i];" +
                    "    if(t.innerText && t.innerText.indexOf('扫')>=0){" +
                    "      t.click(); return 'clicked:'+t.innerText;" +
                    "    }" +
                    "  }" +
                    "  // 尝试直接点击包含「扫」字的所有可点击元素" +
                    "  var all=document.querySelectorAll('a,li,div,span,button');" +
                    "  for(var j=0;j<all.length;j++){" +
                    "    var el=all[j];" +
                    "    if(el.children.length===0 && el.innerText && el.innerText.trim()==='扫码登录'){" +
                    "      el.click(); return 'clicked2:'+el.innerText;" +
                    "    }" +
                    "  }" +
                    "  return 'not_found';" +
                    "})()";

                view.evaluateJavascript(js, result ->
                    Log.d(TAG, "JS qrcode switch: " + result));

                // 如果已经跳转到登录后页面，立即检查 cookie
                if (isSuccessUrl(url)) {
                    mainHandler.postDelayed(() -> checkCookieAndProceed(), 1000);
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

    private boolean isSuccessUrl(String url) {
        if (url == null) return false;
        return url.equals("https://www.zhipin.com/")
            || url.startsWith("https://www.zhipin.com/web/geek/")
            || url.startsWith("https://www.zhipin.com/web/boss/");
    }

    private void checkCookieAndProceed() {
        if (loginHandled || isFinishing()) return;

        CookieManager.getInstance().flush();
        String cookies = CookieManager.getInstance().getCookie("https://www.zhipin.com");
        Log.d(TAG, "polling cookie: " + (cookies != null ? cookies.length() + " chars" : "null"));

        if (cookies != null && cookies.contains("wt2")) {
            loginHandled = true;
            mainHandler.removeCallbacks(cookiePoller);

            getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("cookie", cookies)
                .putBoolean("logged_in", true)
                .putLong("login_time", System.currentTimeMillis())
                .apply();

            Log.d(TAG, "Login success");
            Toast.makeText(this, "登录成功！", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("refresh_after_login", true);
            startActivity(intent);
            finish();
        }
        // 未拿到 wt2 则继续轮询，不做任何提示（避免打扰用户）
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(cookiePoller);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
