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
 * Loads the Boss ZhiPin search page in a hidden WebView, waits for the page
 * JS to finish initialising, then injects a fetch() call from inside the page
 * so all cookies / anti-scraping tokens are handled by the browser engine.
 * The result is passed back via a JavascriptInterface.
 */
public class BossApiClient {

    private static final String TAG        = "BossApiClient";
    private static final int    TIMEOUT_MS = 30_000;   // 30 s total

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

    private WebView      wv;
    private boolean      destroyed = false;

    public BossApiClient(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public boolean isLoggedIn() {
        String c = appCtx.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
                         .getString("cookie", null);
        return c != null && c.contains("wt2");
    }

    public void logout() {
        appCtx.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
              .edit().clear().apply();
    }

    public void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        main.post(this::destroyWv);
    }

    // ── Entry point (must be called on any thread) ───────────────────────

    public void fetchForeignTradeJobs(String cityCode, FetchCallback cb) {
        main.post(() -> {
            if (destroyed) return;
            destroyWv();
            doFetch(cityCode, cb);
        });
    }

    // ── Core: hidden WebView + in-page fetch() ───────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void doFetch(String cityCode, FetchCallback cb) {
        wv = new WebView(appCtx);
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

        // JS bridge — called from inside the page context, so cookies are intact
        Object bridge = new Object() {
            @JavascriptInterface
            public void onResult(String json) {
                Log.d(TAG, "onResult len=" + json.length()
                        + " preview=" + json.substring(0, Math.min(200, json.length())));
                if (done.getAndSet(true)) return;
                main.removeCallbacksAndMessages(null);
                List<JobItem> jobs = parseJoblist(json);
                Log.d(TAG, "parsed jobs=" + jobs.size());
                main.post(() -> {
                    destroyWv();
                    if (jobs.isEmpty()) {
                        cb.onSuccess(generateDemo(), false);
                    } else {
                        cb.onSuccess(jobs, true);
                    }
                });
            }

            @JavascriptInterface
            public void onError(String msg) {
                Log.e(TAG, "JS onError: " + msg);
                if (done.getAndSet(true)) return;
                main.removeCallbacksAndMessages(null);
                main.post(() -> {
                    destroyWv();
                    cb.onSuccess(generateDemo(), false);
                });
            }
        };
        wv.addJavascriptInterface(bridge, "AndroidBridge");

        // Timeout fallback
        Runnable timeoutTask = () -> {
            if (done.getAndSet(true)) return;
            Log.w(TAG, "fetch timeout");
            destroyWv();
            cb.onSuccess(generateDemo(), false);
        };
        main.postDelayed(timeoutTask, TIMEOUT_MS);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                if (done.get()) return;

                // After the page loads, inject fetch() from inside the page.
                // Same-origin fetch — browser uses page cookies automatically.
                String apiUrl = "https://www.zhipin.com/wapi/zpgeek/search/joblist.json"
                        + "?scene=1"
                        + "&query=" + encode("外贸")
                        + "&city=" + cityCode
                        + "&publishTime=1"
                        + "&page=1&pageSize=30";

                String js = "(function() {\n"
                        + "  fetch('" + apiUrl + "', {\n"
                        + "    credentials: 'include',\n"
                        + "    headers: {\n"
                        + "      'Accept': 'application/json, text/plain, */*',\n"
                        + "      'X-Requested-With': 'XMLHttpRequest'\n"
                        + "    }\n"
                        + "  })\n"
                        + "  .then(function(r) { return r.text(); })\n"
                        + "  .then(function(t) { AndroidBridge.onResult(t); })\n"
                        + "  .catch(function(e) { AndroidBridge.onError(e.toString()); });\n"
                        + "})();";

                view.evaluateJavascript(js, null);
            }
        });

        // Load the search page first so we're in same-origin context
        String pageUrl = "https://www.zhipin.com/web/geek/job"
                + "?query=" + encode("外贸")
                + "&city=" + cityCode
                + "&publishTime=1";
        Log.d(TAG, "loadUrl: " + pageUrl);
        wv.loadUrl(pageUrl);
    }

    // ── Parse joblist JSON ───────────────────────────────────────────────

    private List<JobItem> parseJoblist(String json) {
        List<JobItem> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : -1;
            if (code != 0) {
                Log.w(TAG, "API code=" + code
                        + " msg=" + (root.has("message") ? root.get("message") : "?"));
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
                    String jobUrl = encId.isEmpty()
                            ? "https://www.zhipin.com"
                            : "https://www.zhipin.com/job_detail/" + encId + ".html";
                    out.add(new JobItem(
                            str(j, "jobName"), str(j, "brandName"),
                            addr(city, area, biz), city, area,
                            str(j, "salaryDesc"), time, jobUrl,
                            str(j, "brandIndustry"), str(j, "brandScaleName")));
                } catch (Exception e) {
                    Log.w(TAG, "skip job: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseJoblist: " + e.getMessage());
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void destroyWv() {
        if (wv == null) return;
        try { wv.stopLoading(); wv.setWebViewClient(null); wv.destroy(); }
        catch (Exception ignored) {}
        wv = null;
    }

    private String addr(String city, String area, String biz) {
        StringBuilder sb = new StringBuilder();
        if (!mt(city)) sb.append(city);
        if (!mt(area)) { if (sb.length() > 0) sb.append(" · "); sb.append(area); }
        if (!mt(biz))  { if (sb.length() > 0) sb.append(" · "); sb.append(biz); }
        return sb.length() > 0 ? sb.toString() : "地址未知";
    }

    private boolean mt(String s) { return s == null || s.isEmpty(); }

    private String str(JsonObject o, String k) {
        try {
            JsonElement e = o.get(k);
            return (e != null && !e.isJsonNull()) ? e.getAsString() : "";
        } catch (Exception x) { return ""; }
    }

    private String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
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
            list.add(new JobItem(r[0], r[1], r[2] + "·" + r[3], r[2], r[3],
                    r[4], t, "https://www.zhipin.com/", r[5], r[6]));
        return list;
    }
}
