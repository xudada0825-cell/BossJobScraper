package com.bossscraper.app.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.bossscraper.app.model.JobItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Strategy: load the Boss ZhiPin search page in a hidden WebView so the
 * browser JS engine generates all anti-scraping tokens (__zp_stoken__ etc.)
 * naturally. We intercept the XHR call to /wapi/zpgeek/search/joblist.json
 * via shouldInterceptRequest, forward it with OkHttp (carrying the exact
 * same headers the WebView would use), capture the JSON response, then
 * return it to the WebView untouched so the page also renders normally.
 */
public class BossApiClient {

    private static final String TAG        = "BossApiClient";
    private static final String API_PATH   = "/wapi/zpgeek/search/joblist.json";
    private static final int    TIMEOUT_MS = 20_000;

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

    private final Context   appCtx;
    private final Handler   main    = new Handler(Looper.getMainLooper());
    private final OkHttpClient http  = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

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

    // ── Entry point ──────────────────────────────────────────────────────

    public void fetchForeignTradeJobs(String cityCode, FetchCallback cb) {
        main.post(() -> {
            if (destroyed) return;
            destroyWv();
            doFetch(cityCode, cb);
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void doFetch(String cityCode, FetchCallback cb) {
        wv = new WebView(appCtx);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36");

        List<JobItem> collected = new ArrayList<>();
        AtomicBoolean done      = new AtomicBoolean(false);

        Runnable timeout = () -> {
            if (done.getAndSet(true)) return;
            Log.w(TAG, "timeout — collected=" + collected.size());
            destroyWv();
            deliver(collected, cb);
        };
        main.postDelayed(timeout, TIMEOUT_MS);

        wv.setWebViewClient(new WebViewClient() {

            // Intercept every network request the WebView makes
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest req) {

                String url = req.getUrl().toString();
                if (!url.contains(API_PATH)) {
                    // Not the API — let WebView handle it normally
                    return null;
                }

                Log.d(TAG, "Intercepted: " + url);

                // Forward the exact same request via OkHttp
                try {
                    okhttp3.Request.Builder rb = new Request.Builder().url(url).get();
                    // Copy all headers the WebView set (includes Cookie, stoken, etc.)
                    for (java.util.Map.Entry<String, String> e :
                            req.getRequestHeaders().entrySet()) {
                        rb.header(e.getKey(), e.getValue());
                    }
                    // Make sure Referer is set
                    if (!req.getRequestHeaders().containsKey("Referer")) {
                        rb.header("Referer",
                                "https://www.zhipin.com/web/geek/job?city=" + cityCode);
                    }

                    Response resp = http.newCall(rb.build()).execute();
                    byte[] body = resp.body() != null ? resp.body().bytes() : new byte[0];
                    String bodyStr = new String(body, "UTF-8");
                    Log.d(TAG, "API response: " + bodyStr.substring(0,
                            Math.min(300, bodyStr.length())));

                    // Parse jobs
                    List<JobItem> parsed = parseJoblist(bodyStr);
                    Log.d(TAG, "Parsed " + parsed.size() + " jobs");
                    collected.addAll(parsed);

                    // Deliver as soon as we have data (don't wait for page render)
                    if (!parsed.isEmpty() && !done.getAndSet(true)) {
                        main.removeCallbacks(timeout);
                        destroyWv();
                        main.post(() -> cb.onSuccess(new ArrayList<>(collected), true));
                    }

                    // Return the body to the WebView so page also renders
                    String ct = resp.header("Content-Type", "application/json; charset=utf-8");
                    resp.close();
                    return new WebResourceResponse(
                            ct.split(";")[0].trim(),
                            "UTF-8",
                            resp.code(),
                            "OK",
                            new java.util.HashMap<>(),
                            new ByteArrayInputStream(body));

                } catch (Exception e) {
                    Log.e(TAG, "intercept error: " + e.getMessage());
                    return null;
                }
            }
        });

        String searchUrl = "https://www.zhipin.com/web/geek/job"
                + "?query=%E5%A4%96%E8%B4%B8"
                + "&city=" + cityCode
                + "&publishTime=1";
        Log.d(TAG, "Loading: " + searchUrl);
        wv.loadUrl(searchUrl);
    }

    // ── Parse /wapi/zpgeek/search/joblist.json response ──────────────────

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
            JsonArray list = zpData.has("jobList")
                    ? zpData.getAsJsonArray("jobList") : null;
            if (list == null) return out;

            String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
            for (JsonElement el : list) {
                try {
                    JsonObject j = el.getAsJsonObject();
                    String city  = str(j, "cityName");
                    String area  = str(j, "areaDistrict");
                    String biz   = str(j, "businessDistrict");
                    String encId = str(j, "encryptJobId");
                    String url   = encId.isEmpty()
                            ? "https://www.zhipin.com"
                            : "https://www.zhipin.com/job_detail/" + encId + ".html";
                    out.add(new JobItem(
                            str(j, "jobName"), str(j, "brandName"),
                            addr(city, area, biz), city, area,
                            str(j, "salaryDesc"), time, url,
                            str(j, "brandIndustry"), str(j, "brandScaleName")));
                } catch (Exception e) {
                    Log.w(TAG, "skip: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseJoblist: " + e.getMessage());
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void deliver(List<JobItem> jobs, FetchCallback cb) {
        if (jobs.isEmpty()) {
            cb.onSuccess(generateDemo(), false);
        } else {
            cb.onSuccess(new ArrayList<>(jobs), true);
        }
    }

    private void destroyWv() {
        if (wv == null) return;
        try { wv.stopLoading(); wv.setWebViewClient(null); wv.destroy(); }
        catch (Exception ignored) {}
        wv = null;
    }

    public void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        main.post(this::destroyWv);
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
            {"外贸业务员","广州盛达进出口","广州","天河","8-15K","贸易","100-499人"},
            {"外贸跟单","深圳优贸供应链","深圳","福田","7-12K","供应链","50-99人"},
            {"国际贸易经理","义乌汇通进出口","义乌","北苑","15-25K","贸易","500-999人"},
            {"跨境电商运营","宁波跨境通电商","宁波","江北","10-18K","电商","100-499人"},
            {"外贸销售代表","上海亚太国际","上海","浦东","12-20K","贸易","200-499人"},
        };
        for (String[] r : d)
            list.add(new JobItem(r[0],r[1],r[2]+"·"+r[3],r[2],r[3],r[4],t,
                    "https://www.zhipin.com/",r[5],r[6]));
        return list;
    }
}
