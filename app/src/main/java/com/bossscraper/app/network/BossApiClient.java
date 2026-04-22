package com.bossscraper.app.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.bossscraper.app.model.JobItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches jobs by reusing the SAME WebView that was used for login,
 * or creating a new one for guest access. The key insight: after a
 * real user login in WebView, all session cookies are already present.
 * We inject a same-origin fetch() via JS so all tokens are handled by
 * the browser engine automatically.
 */
public class BossApiClient {

    private static final String TAG        = "BossApiClient";
    private static final int    TIMEOUT_MS = 25_000;

    public static final String[][] CITY_CODES = {
            {"全国",  "100010000"}, {"北京",  "101010100"}, {"上海",  "101020100"},
            {"广州",  "101280100"}, {"深圳",  "101280600"}, {"杭州",  "101210100"},
            {"成都",  "101270100"}, {"武汉",  "101200100"}, {"南京",  "101190100"},
            {"西安",  "101110100"}, {"苏州",  "101190400"}, {"宁波",  "101210400"},
            {"青岛",  "101120200"}, {"天津",  "101030100"}, {"重庆",  "101040100"},
            {"厦门",  "101230200"}, {"郑州",  "101180100"}, {"福州",  "101230100"},
            {"长沙",  "101250100"}, {"东莞",  "101281600"}, {"义乌",  "101210600"},
    };

    public interface FetchCallback {
        void onSuccess(List<JobItem> jobs, boolean isRealData);
        void onError(String errorMsg);
    }

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());

    // The shared WebView — set from LoginActivity after successful login
    private static WebView sharedWebView;
    private static String  sharedWebViewUrl; // last URL loaded in sharedWebView

    private boolean destroyed = false;

    public BossApiClient(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    /** Called by LoginActivity after login succeeds, before destroying the WebView. */
    public static void setSharedWebView(WebView wv, String currentUrl) {
        sharedWebView    = wv;
        sharedWebViewUrl = currentUrl;
        Log.d("BossApiClient", "sharedWebView set, url=" + currentUrl);
    }

    public static void clearSharedWebView() {
        sharedWebView    = null;
        sharedWebViewUrl = null;
    }

    public boolean isLoggedIn() {
        String c = appCtx.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
                         .getString("cookie", null);
        return c != null && c.contains("wt2");
    }

    public void logout() {
        appCtx.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
              .edit().clear().apply();
        clearSharedWebView();
    }

    public void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
    }

    // ── Entry point ──────────────────────────────────────────────────────

    public void fetchForeignTradeJobs(String cityCode, FetchCallback cb) {
        main.post(() -> {
            if (destroyed) return;
            if (sharedWebView != null) {
                fetchViaSharedWebView(cityCode, cb);
            } else {
                fetchViaNewWebView(cityCode, cb);
            }
        });
    }

    // ── Strategy A: reuse the login WebView (has full session) ───────────

    private void fetchViaSharedWebView(String cityCode, FetchCallback cb) {
        WebView wv = sharedWebView;
        AtomicBoolean done = new AtomicBoolean(false);

        // Add JS bridge temporarily
        String bridgeName = "AndroidBridge" + System.currentTimeMillis();

        Object bridge = new Object() {
            @JavascriptInterface
            public void onResult(String json) {
                if (done.getAndSet(true)) return;
                main.removeCallbacksAndMessages(null);
                Log.d(TAG, "sharedWV result len=" + json.length());
                List<JobItem> jobs = parseJoblist(json);
                Log.d(TAG, "parsed=" + jobs.size());
                main.post(() -> {
                    if (jobs.isEmpty()) cb.onSuccess(generateDemo(), false);
                    else cb.onSuccess(jobs, true);
                });
            }
            @JavascriptInterface
            public void onError(String msg) {
                if (done.getAndSet(true)) return;
                main.removeCallbacksAndMessages(null);
                Log.e(TAG, "sharedWV JS error: " + msg);
                main.post(() -> cb.onSuccess(generateDemo(), false));
            }
        };

        wv.addJavascriptInterface(bridge, bridgeName);

        main.postDelayed(() -> {
            if (done.getAndSet(true)) return;
            Log.w(TAG, "sharedWV timeout, falling back to new WebView");
            fetchViaNewWebView(cityCode, cb);
        }, TIMEOUT_MS);

        // If the shared WebView is already on zhipin.com, inject fetch directly.
        // Otherwise navigate to search page first.
        String currentUrl = sharedWebViewUrl != null ? sharedWebViewUrl : "";
        if (currentUrl.contains("zhipin.com")) {
            injectFetch(wv, bridgeName, cityCode);
        } else {
            // Need to navigate first — wait for onPageFinished
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (done.get()) return;
                    sharedWebViewUrl = url;
                    if (url != null && url.contains("zhipin.com")) {
                        injectFetch(view, bridgeName, cityCode);
                    }
                }
            });
            wv.loadUrl("https://www.zhipin.com/web/geek/job?query=%E5%A4%96%E8%B4%B8&city=" + cityCode);
        }
    }

    // ── Strategy B: new WebView (for non-logged-in, loads page fresh) ───

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void fetchViaNewWebView(String cityCode, FetchCallback cb) {
        WebView wv = new WebView(appCtx);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.6367.82 Mobile Safari/537.36");

        AtomicBoolean done = new AtomicBoolean(false);

        Object bridge = new Object() {
            @JavascriptInterface
            public void onResult(String json) {
                if (done.getAndSet(true)) return;
                main.removeCallbacksAndMessages(null);
                Log.d(TAG, "newWV result len=" + json.length());
                List<JobItem> jobs = parseJoblist(json);
                Log.d(TAG, "parsed=" + jobs.size());
                main.post(() -> {
                    destroyWv(wv);
                    if (jobs.isEmpty()) cb.onSuccess(generateDemo(), false);
                    else cb.onSuccess(jobs, true);
                });
            }
            @JavascriptInterface
            public void onError(String msg) {
                if (done.getAndSet(true)) return;
                main.removeCallbacksAndMessages(null);
                Log.e(TAG, "newWV JS error: " + msg);
                main.post(() -> { destroyWv(wv); cb.onSuccess(generateDemo(), false); });
            }
        };
        wv.addJavascriptInterface(bridge, "AndroidBridge");

        main.postDelayed(() -> {
            if (done.getAndSet(true)) return;
            Log.w(TAG, "newWV timeout");
            main.post(() -> { destroyWv(wv); cb.onSuccess(generateDemo(), false); });
        }, TIMEOUT_MS);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (done.get()) return;
                Log.d(TAG, "newWV onPageFinished: " + url);
                injectFetch(view, "AndroidBridge", cityCode);
            }
        });

        String url = "https://www.zhipin.com/web/geek/job"
                + "?query=%E5%A4%96%E8%B4%B8&city=" + cityCode + "&publishTime=1";
        Log.d(TAG, "newWV loadUrl: " + url);
        wv.loadUrl(url);
    }

    // ── Inject same-origin fetch() into the running page ────────────────

    private void injectFetch(WebView wv, String bridgeName, String cityCode) {
        String apiUrl = "https://www.zhipin.com/wapi/zpgeek/search/joblist.json"
                + "?scene=1&query=%E5%A4%96%E8%B4%B8&city=" + cityCode
                + "&publishTime=1&page=1&pageSize=30";

        String js =
            "(function(){\n" +
            "  fetch('" + apiUrl + "',{\n" +
            "    credentials:'include',\n" +
            "    headers:{'Accept':'application/json','X-Requested-With':'XMLHttpRequest'}\n" +
            "  })\n" +
            "  .then(function(r){return r.text();})\n" +
            "  .then(function(t){" + bridgeName + ".onResult(t);})\n" +
            "  .catch(function(e){" + bridgeName + ".onError(e.toString());});\n" +
            "})();";

        Log.d(TAG, "injectFetch apiUrl=" + apiUrl);
        wv.evaluateJavascript(js, null);
    }

    // ── Parse response ───────────────────────────────────────────────────

    private List<JobItem> parseJoblist(String json) {
        List<JobItem> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : -1;
            if (code != 0) {
                Log.w(TAG, "API code=" + code + " msg="
                        + (root.has("message") ? root.get("message").getAsString() : "?"));
                return out;
            }
            JsonObject zpData = root.has("zpData") ? root.getAsJsonObject("zpData") : null;
            if (zpData == null) return out;
            JsonArray list = zpData.has("jobList") ? zpData.getAsJsonArray("jobList") : null;
            if (list == null) return out;

            String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
            for (JsonElement el : list) {
                try {
                    JsonObject j  = el.getAsJsonObject();
                    String city   = str(j, "cityName");
                    String area   = str(j, "areaDistrict");
                    String biz    = str(j, "businessDistrict");
                    String encId  = str(j, "encryptJobId");
                    String jobUrl = encId.isEmpty() ? "https://www.zhipin.com"
                            : "https://www.zhipin.com/job_detail/" + encId + ".html";
                    out.add(new JobItem(
                            str(j, "jobName"), str(j, "brandName"),
                            addr(city, area, biz), city, area,
                            str(j, "salaryDesc"), time, jobUrl,
                            str(j, "brandIndustry"), str(j, "brandScaleName")));
                } catch (Exception e) {
                    Log.w(TAG, "skip: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parse: " + e.getMessage());
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void destroyWv(WebView wv) {
        if (wv == null) return;
        try { wv.stopLoading(); wv.setWebViewClient(null); wv.destroy(); }
        catch (Exception ignored) {}
    }

    private String addr(String city, String area, String biz) {
        StringBuilder sb = new StringBuilder();
        if (!mt(city)) sb.append(city);
        if (!mt(area)) { if (sb.length()>0) sb.append(" · "); sb.append(area); }
        if (!mt(biz))  { if (sb.length()>0) sb.append(" · "); sb.append(biz); }
        return sb.length()>0 ? sb.toString() : "地址未知";
    }

    private boolean mt(String s) { return s==null||s.isEmpty(); }

    private String str(JsonObject o, String k) {
        try { JsonElement e=o.get(k); return e!=null&&!e.isJsonNull()?e.getAsString():""; }
        catch(Exception x){return "";}
    }

    private List<JobItem> generateDemo() {
        List<JobItem> list = new ArrayList<>();
        String t = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        String[][] d = {
            {"外贸业务员",   "广州盛达进出口", "广州", "天河",  "8-15K",  "贸易",   "100-499人"},
            {"外贸跟单",     "深圳优贸供应链", "深圳", "福田",  "7-12K",  "供应链", "50-99人"},
            {"国际贸易经理", "义乌汇通进出口", "义乌", "北苑",  "15-25K", "贸易",   "500-999人"},
            {"跨境电商运营", "宁波跨境通电商", "宁波", "江北",  "10-18K", "电商",   "100-499人"},
            {"外贸销售代表", "上海亚太国际",   "上海", "浦东",  "12-20K", "贸易",   "200-499人"},
        };
        for (String[] r : d)
            list.add(new JobItem(r[0],r[1],r[2]+"·"+r[3],r[2],r[3],r[4],t,
                    "https://www.zhipin.com/",r[5],r[6]));
        return list;
    }
}
