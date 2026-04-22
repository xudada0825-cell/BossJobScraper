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

/**
 * Fetches Boss ZhiPin job listings by loading the real search page inside
 * a hidden WebView, then extracting window.__INITIAL_STATE__ via JS.
 * This approach bypasses all server-side anti-scraping tokens (__zp_stoken__
 * etc.) because the browser JS engine generates them natively.
 */
public class BossApiClient {

    private static final String TAG = "BossApiClient";

    // Keywords to search in order; results from all are merged
    private static final String[] KEYWORDS = {"外贸", "国际贸易", "跨境电商"};

    // Page load timeout: if JS hasn't fired after this many ms, try next keyword
    private static final int TIMEOUT_MS = 12_000;

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

    private final Context       context;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    // The hidden WebView is kept alive for the duration of the fetch session
    private WebView             hiddenWebView;
    private boolean             destroyed = false;

    public BossApiClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isLoggedIn() {
        String c = context.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
                          .getString("cookie", null);
        return c != null && c.contains("wt2");
    }

    public void logout() {
        context.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
               .edit().clear().apply();
    }

    // ── Public entry point ───────────────────────────────────────────────

    public void fetchForeignTradeJobs(String cityCode, FetchCallback callback) {
        mainHandler.post(() -> {
            if (destroyed) return;
            destroyWebView();               // clean up any previous session
            fetchKeyword(KEYWORDS[0], cityCode, new ArrayList<>(), 0, callback);
        });
    }

    // ── Per-keyword WebView load ─────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void fetchKeyword(String keyword, String cityCode,
                              List<JobItem> accumulated, int keywordIndex,
                              FetchCallback callback) {
        if (destroyed) return;

        hiddenWebView = new WebView(context);
        WebSettings s = hiddenWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(false);
        s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36");
        // Disable image/media loads to speed up scraping
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        // Allow mixed content (zhipin uses https, this is fine)
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Result holder shared between JS bridge and timeout runnable
        final boolean[] called = {false};

        JsBridge bridge = new JsBridge() {
            @Override
            public void onData(String json) {
                if (called[0]) return;
                called[0] = true;
                mainHandler.removeCallbacksAndMessages(null);
                Log.d(TAG, "JS bridge received " + json.length() + " chars for " + keyword);

                try {
                    List<JobItem> parsed = parseInitialState(json, cityCode);
                    Log.d(TAG, "Parsed " + parsed.size() + " jobs for keyword=" + keyword);
                    accumulated.addAll(parsed);
                } catch (Exception e) {
                    Log.e(TAG, "parse error: " + e.getMessage());
                }

                destroyWebView();
                int next = keywordIndex + 1;
                if (next < KEYWORDS.length) {
                    fetchKeyword(KEYWORDS[next], cityCode, accumulated, next, callback);
                } else {
                    deliverResult(accumulated, callback);
                }
            }
        };

        hiddenWebView.addJavascriptInterface(bridge, "AndroidBridge");

        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                // Inject JS to pull window.__INITIAL_STATE__ once DOM is ready
                String js =
                    "(function() {" +
                    "  try {" +
                    "    var state = window.__INITIAL_STATE__;" +
                    "    if (state) {" +
                    "      AndroidBridge.onData(JSON.stringify(state));" +
                    "    } else {" +
                    "      // Fallback: wait 1.5 s and retry once" +
                    "      setTimeout(function() {" +
                    "        var s2 = window.__INITIAL_STATE__;" +
                    "        AndroidBridge.onData(s2 ? JSON.stringify(s2) : '{}');" +
                    "      }, 1500);" +
                    "    }" +
                    "  } catch(e) {" +
                    "    AndroidBridge.onData('{}');" +
                    "  }" +
                    "})();";
                view.evaluateJavascript(js, null);
            }
        });

        // Timeout guard
        Runnable timeoutTask = () -> {
            if (called[0]) return;
            called[0] = true;
            Log.w(TAG, "Timeout for keyword=" + keyword);
            destroyWebView();
            int next = keywordIndex + 1;
            if (next < KEYWORDS.length) {
                fetchKeyword(KEYWORDS[next], cityCode, accumulated, next, callback);
            } else {
                deliverResult(accumulated, callback);
            }
        };
        mainHandler.postDelayed(timeoutTask, TIMEOUT_MS);

        String url = "https://www.zhipin.com/web/geek/job"
                   + "?query=" + encode(keyword)
                   + "&city=" + cityCode
                   + "&publishTime=1";
        Log.d(TAG, "Loading: " + url);
        hiddenWebView.loadUrl(url);
    }

    private void deliverResult(List<JobItem> accumulated, FetchCallback callback) {
        if (accumulated.isEmpty()) {
            Log.w(TAG, "All keywords returned empty, using demo data");
            callback.onSuccess(generateDemoData(), false);
        } else {
            callback.onSuccess(new ArrayList<>(accumulated), true);
        }
    }

    // ── JS interface ─────────────────────────────────────────────────────

    // Must be an abstract class or interface annotated with @JavascriptInterface
    private abstract static class JsBridge {
        @JavascriptInterface
        public abstract void onData(String json);
    }

    // ── Parse window.__INITIAL_STATE__ ───────────────────────────────────

    private List<JobItem> parseInitialState(String json, String cityCode) {
        List<JobItem> result = new ArrayList<>();
        if (json == null || json.equals("{}") || json.isEmpty()) return result;

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // Typical path: __INITIAL_STATE__.zpData.jobList  (Boss web app)
            JsonArray jobList = null;

            // Try zpData -> jobList
            if (root.has("zpData")) {
                JsonObject zpData = root.getAsJsonObject("zpData");
                if (zpData.has("jobList")) jobList = zpData.getAsJsonArray("jobList");
            }

            // Try geek -> jobList  (alternate structure)
            if (jobList == null && root.has("geek")) {
                JsonObject geek = root.getAsJsonObject("geek");
                if (geek.has("jobList")) jobList = geek.getAsJsonArray("jobList");
            }

            // Try direct jobList at root
            if (jobList == null && root.has("jobList")) {
                jobList = root.getAsJsonArray("jobList");
            }

            if (jobList == null) {
                Log.w(TAG, "No jobList found in __INITIAL_STATE__. Keys: " + root.keySet());
                return result;
            }

            String timeStr = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());

            for (JsonElement el : jobList) {
                try {
                    JsonObject job = el.getAsJsonObject();
                    String city = str(job, "cityName");
                    String area = str(job, "areaDistrict");
                    String biz  = str(job, "businessDistrict");
                    String encId = str(job, "encryptJobId");
                    String jobUrl = encId.isEmpty()
                            ? "https://www.zhipin.com"
                            : "https://www.zhipin.com/job_detail/" + encId + ".html";
                    result.add(new JobItem(
                            str(job, "jobName"),
                            str(job, "brandName"),
                            addr(city, area, biz),
                            city, area,
                            str(job, "salaryDesc"),
                            timeStr,
                            jobUrl,
                            str(job, "brandIndustry"),
                            str(job, "brandScaleName")
                    ));
                } catch (Exception e) {
                    Log.w(TAG, "skip job item: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseInitialState: " + e.getMessage());
        }
        return result;
    }

    // ── Utilities ────────────────────────────────────────────────────────

    private void destroyWebView() {
        if (hiddenWebView == null) return;
        try {
            hiddenWebView.stopLoading();
            hiddenWebView.setWebViewClient(null);
            hiddenWebView.destroy();
        } catch (Exception ignored) {}
        hiddenWebView = null;
    }

    public void destroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        destroyWebView();
    }

    private String addr(String city, String area, String biz) {
        StringBuilder sb = new StringBuilder();
        if (!empty(city)) sb.append(city);
        if (!empty(area)) { if (sb.length() > 0) sb.append(" · "); sb.append(area); }
        if (!empty(biz))  { if (sb.length() > 0) sb.append(" · "); sb.append(biz); }
        return sb.length() > 0 ? sb.toString() : "地址未知";
    }

    private boolean empty(String s) { return s == null || s.isEmpty(); }

    private String str(JsonObject o, String k) {
        try {
            JsonElement e = o.get(k);
            return (e != null && !e.isJsonNull()) ? e.getAsString() : "";
        } catch (Exception ignored) { return ""; }
    }

    private String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private List<JobItem> generateDemoData() {
        List<JobItem> list = new ArrayList<>();
        String[][] data = {
            {"外贸业务员",   "广州盛达进出口贸易有限公司","广州","天河区","8-15K","贸易/进出口","100-499人"},
            {"外贸跟单",     "深圳优贸供应链有限公司",   "深圳","福田区","7-12K","供应链/物流","50-99人"},
            {"国际贸易经理", "义乌汇通进出口有限公司",   "义乌","北苑", "15-25K","贸易/进出口","500-999人"},
            {"跨境电商运营", "宁波跨境通电商科技有限公司","宁波","江北区","10-18K","电商/新零售","100-499人"},
            {"外贸销售代表", "上海亚太国际贸易有限公司", "上海","浦东新区","12-20K","贸易/进出口","200-499人"},
            {"外贸业务经理", "青岛海盛贸易有限公司",     "青岛","市南区","15-30K","贸易/进出口","100-499人"},
            {"外贸单证员",   "厦门远洋货运代理有限公司", "厦门","湖里区","6-10K","货运/物流","50-99人"},
            {"跨境贸易专员", "东莞华创跨境电商有限公司", "东莞","南城区","8-13K","电商/新零售","100-499人"},
        };
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        for (String[] d : data) {
            list.add(new JobItem(d[0], d[1], d[2] + "·" + d[3], d[2], d[3],
                    d[4], time, "https://www.zhipin.com/", d[5], d[6]));
        }
        return list;
    }
}
