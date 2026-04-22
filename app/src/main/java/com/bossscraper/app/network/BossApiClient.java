package com.bossscraper.app.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.bossscraper.app.model.JobItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches real job listings from Zhaopin mobile site (m.zhaopin.com).
 *
 * Strategy:
 *   1. Load m.zhaopin.com/sou/?kw=waiмао&cityId=XXX in a hidden WebView
 *   2. onPageFinished -> inject JS to extract window.__INITIAL_DATA__.positionList as JSON
 *   3. Parse JSON in Java -> List<JobItem>
 *
 * This avoids HTML regex and unicode-escape issues entirely.
 */
public class BossApiClient {

    private static final String TAG        = "BossApiClient";
    private static final int    TIMEOUT_MS = 25_000;

    // Zhaopin city IDs
    public static final String[][] CITY_CODES = {
            {"全国",  "530"},
            {"北京",  "2"},
            {"上海",  "10"},
            {"广州",  "763"},
            {"深圳",  "765"},
            {"杭州",  "66"},
            {"成都",  "801"},
            {"武汉",  "736"},
            {"南京",  "73"},
            {"西安",  "854"},
            {"苏州",  "75"},
            {"宁波",  "743"},
            {"青岛",  "576"},
            {"天津",  "3"},
            {"重庆",  "4"},
            {"厦门",  "745"},
            {"郑州",  "763"},
            {"福州",  "746"},
            {"长沙",  "749"},
            {"东莞",  "769"},
            {"义乌",  "781"},
    };

    public interface FetchCallback {
        void onSuccess(List<JobItem> jobs, boolean isRealData);
        void onError(String errorMsg);
    }

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    public BossApiClient(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public boolean isLoggedIn() { return false; }
    public void logout() {}
    public void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
    }

    // ── Entry point ──────────────────────────────────────────────────────

    public void fetchForeignTradeJobs(String cityCode, FetchCallback cb) {
        main.post(() -> {
            if (destroyed) return;
            loadAndParse(cityCode, cb);
        });
    }

    // ── WebView load + JS extraction ─────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void loadAndParse(String cityCode, FetchCallback cb) {
        final WebView wv = new WebView(appCtx);
        final AtomicBoolean done = new AtomicBoolean(false);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.6367.82 Mobile Safari/537.36");

        Runnable timeoutTask = () -> {
            if (done.getAndSet(true)) return;
            Log.w(TAG, "timeout");
            destroyWv(wv);
            cb.onError("加载超时，请检查网络");
        };
        main.postDelayed(timeoutTask, TIMEOUT_MS);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (done.get()) return;
                Log.d(TAG, "onPageFinished: " + url);

                // Extract positionList JSON directly from window.__INITIAL_DATA__
                // We serialize only the fields we need to keep the string small.
                String js =
                    "(function() {" +
                    "  try {" +
                    "    var data = window.__INITIAL_DATA__;" +
                    "    if (!data || !data.positionList) return 'NO_DATA';" +
                    "    var list = data.positionList;" +
                    "    var out = [];" +
                    "    for (var i = 0; i < list.length; i++) {" +
                    "      var j = list[i];" +
                    "      var card = {};" +
                    "      try { card = JSON.parse(j.cardCustomJson || '{}'); } catch(e) {}" +
                    "      out.push({" +
                    "        name: j.name || ''," +
                    "        salary: j.salary60 || ''," +
                    "        company: j.companyName || ''," +
                    "        city: j.workCity || ''," +
                    "        district: j.cityDistrict || ''," +
                    "        street: j.streetName || ''," +
                    "        industry: j.industryName || ''," +
                    "        number: j.number || ''" +
                    "      });" +
                    "    }" +
                    "    return JSON.stringify(out);" +
                    "  } catch(e) { return 'ERR:' + e.message; }" +
                    "})()";

                view.evaluateJavascript(js, raw -> {
                    if (done.getAndSet(true)) return;
                    main.removeCallbacks(timeoutTask);
                    destroyWv(wv);

                    Log.d(TAG, "JS result length=" + (raw != null ? raw.length() : 0));

                    // evaluateJavascript wraps result in JSON string quotes
                    String jsonStr = unquoteJs(raw);
                    Log.d(TAG, "unquoted prefix=" + jsonStr.substring(0, Math.min(200, jsonStr.length())));

                    if (jsonStr.startsWith("NO_DATA") || jsonStr.startsWith("ERR") || jsonStr.isEmpty()) {
                        cb.onError("暂无数据，请稍后重试");
                        return;
                    }

                    List<JobItem> jobs = parseJsonJobs(jsonStr);
                    Log.d(TAG, "parsed jobs=" + jobs.size());

                    if (jobs.isEmpty()) {
                        cb.onError("暂无招聘数据，请稍后重试");
                    } else {
                        cb.onSuccess(jobs, true);
                    }
                });
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    Log.e(TAG, "WebView error: " + (err != null ? err.getDescription() : "?"));
                }
            }
        });

        String url = "https://m.zhaopin.com/sou/"
                + "?kw=%E5%A4%96%E8%B4%B8"
                + "&cityId=" + cityCode;
        Log.d(TAG, "loadUrl: " + url);
        wv.loadUrl(url);
    }

    // ── JSON parser ───────────────────────────────────────────────────────

    private List<JobItem> parseJsonJobs(String json) {
        List<JobItem> out = new ArrayList<>();
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String jobName  = o.optString("name", "");
                String salary   = o.optString("salary", "薪资面议");
                String company  = o.optString("company", "招聘企业");
                String city     = o.optString("city", "");
                String district = o.optString("district", "");
                String street   = o.optString("street", "");
                String industry = o.optString("industry", "贸易/进出口");
                String number   = o.optString("number", "");

                if (jobName.isEmpty()) continue;
                if (salary.isEmpty()) salary = "薪资面议";
                if (company.isEmpty()) company = "招聘企业";

                String addr = city;
                if (!district.isEmpty()) addr += " " + district;
                if (!street.isEmpty()) addr += " " + street;
                if (addr.trim().isEmpty()) addr = "地址待定";

                String jobUrl = number.isEmpty()
                        ? "https://m.zhaopin.com/"
                        : "https://jobs.zhaopin.com/" + number + ".htm";

                out.add(new JobItem(
                        jobName, company, addr, city, district,
                        salary, time, jobUrl, industry, ""));
            }
        } catch (Exception e) {
            Log.e(TAG, "JSON parse error: " + e.getMessage());
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * evaluateJavascript returns a JSON-encoded string with surrounding quotes
     * and backslash escapes. This method decodes it to the original string.
     * We handle unicode escapes (backslash + u + 4 hex digits) properly
     * so Chinese characters survive.
     */
    private String unquoteJs(String jsVal) {
        if (jsVal == null) return "";
        // Remove surrounding JSON string quotes added by evaluateJavascript
        if (jsVal.startsWith("\"") && jsVal.endsWith("\"")) {
            jsVal = jsVal.substring(1, jsVal.length() - 1);
        }
        // Use StringBuilder to process escape sequences manually
        StringBuilder sb = new StringBuilder(jsVal.length());
        int i = 0;
        while (i < jsVal.length()) {
            char c = jsVal.charAt(i);
            if (c == '\\' && i + 1 < jsVal.length()) {
                char next = jsVal.charAt(i + 1);
                switch (next) {
                    case 'n':  sb.append('\n'); i += 2; break;
                    case 't':  sb.append('\t'); i += 2; break;
                    case 'r':  i += 2; break;
                    case '"':  sb.append('"');  i += 2; break;
                    case '\'': sb.append('\''); i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case 'u':
                        if (i + 5 < jsVal.length()) {
                            try {
                                int cp = Integer.parseInt(jsVal.substring(i + 2, i + 6), 16);
                                sb.append((char) cp);
                                i += 6;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                                i++;
                            }
                        } else {
                            sb.append(c);
                            i++;
                        }
                        break;
                    default:
                        sb.append(c);
                        i++;
                        break;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private void destroyWv(WebView wv) {
        if (wv == null) return;
        try { wv.stopLoading(); wv.setWebViewClient(null); wv.destroy(); }
        catch (Exception ignored) {}
    }
}
