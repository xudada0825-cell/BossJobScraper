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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches real job listings from Zhaopin mobile site (m.zhaopin.com).
 * The mobile site renders job cards server-side (SSR) in the HTML,
 * so we can extract them from the page source without any API calls.
 *
 * Data flow:
 *   1. Load m.zhaopin.com/sou/?kw=外贸&cityId=XXX in a hidden WebView
 *   2. onPageFinished -> inject JS to get document.documentElement.outerHTML
 *   3. Parse HTML via regex to extract job cards
 *   4. Return results via callback
 */
public class BossApiClient {

    private static final String TAG        = "BossApiClient";
    private static final int    TIMEOUT_MS = 20_000;

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

    public boolean isLoggedIn() { return false; } // no login needed
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

    // ── WebView load + HTML extraction ───────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void loadAndParse(String cityCode, FetchCallback cb) {
        final WebView wv = new WebView(appCtx);
        final AtomicBoolean done = new AtomicBoolean(false);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        // Mobile UA - zhaopin serves SSR HTML to mobile browsers
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

                // Extract full HTML via JS and send back to Java
                view.evaluateJavascript(
                    "(function(){ return document.documentElement.outerHTML; })()",
                    html -> {
                        if (done.getAndSet(true)) return;
                        main.removeCallbacks(timeoutTask);
                        destroyWv(wv);

                        // evaluateJavascript returns a JSON string (quoted, escaped)
                        String decoded = unquoteJs(html);
                        Log.d(TAG, "HTML length=" + decoded.length());

                        List<JobItem> jobs = parseZhaopinHtml(decoded);
                        Log.d(TAG, "parsed jobs=" + jobs.size());

                        if (jobs.isEmpty()) {
                            cb.onError("暂无数据，请稍后重试");
                        } else {
                            cb.onSuccess(jobs, true);
                        }
                    });
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        WebResourceError err) {
                if (req.isForMainFrame()) {
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

    // ── HTML parser ──────────────────────────────────────────────────────

    /**
     * Parses Zhaopin mobile HTML job cards.
     *
     * Each card has a job link followed by text in this order:
     *   JOB_NAME  SALARY  EXP  EDU  [TAGS]  COMPANY  INDUSTRY  [SCALE]  CITY  [DISTRICT]
     */
    private List<JobItem> parseZhaopinHtml(String html) {
        List<JobItem> out = new ArrayList<>();
        if (html == null || html.length() < 100) return out;

        // Find all job card anchors
        Pattern urlPat = Pattern.compile(
                "href=\"(https://m\\.zhaopin\\.com/jobs/[^\"]+)\"[^>]*>([^<]{2,60})</a>");
        Matcher m = urlPat.matcher(html);

        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());

        while (m.find()) {
            String jobUrl  = m.group(1);
            String jobName = m.group(2).trim();
            if (jobName.isEmpty()) continue;

            // Grab the next 600 chars of text content after the link
            int end = m.end();
            String chunk = html.substring(end, Math.min(end + 600, html.length()));
            // Strip HTML tags and collapse whitespace
            String text = chunk.replaceAll("<[^>]+>", " ")
                               .replaceAll("\\s+", " ")
                               .trim();

            // Salary: digits-digits元 or 万 pattern
            String salary = extract(text,
                    "(\\d[\\d\\-万]+元[^\\s]*)");

            // Company: look for 有限|集团|公司 etc.
            String company = extract(text,
                    "([^\\s]{2,25}(?:有限公司|集团|股份|工厂|贸易公司|进出口公司|电商))");
            if (company.isEmpty()) {
                // Fallback: long token after edu keywords
                company = extract(text,
                        "(?:本科|大专|硕士|高中|不限)\\s+([^\\s]{3,25})");
            }

            // City: known city names
            String city = extract(text,
                    "(北京|上海|广州|深圳|杭州|成都|武汉|南京|西安|苏州|宁波|青岛|天津|重庆|厦门|郑州|福州|长沙|东莞|义乌|全国)");

            // District: token after city name
            String district = "";
            if (!city.isEmpty()) {
                district = extract(text.substring(text.indexOf(city) + city.length()),
                        "^\\s+([^\\s]{2,6})");
            }

            // Address display
            String addr = city;
            if (!district.isEmpty()) addr += " · " + district;
            if (addr.isEmpty()) addr = "地址待定";

            out.add(new JobItem(
                    jobName,
                    company.isEmpty() ? "招聘企业" : company,
                    addr, city, district,
                    salary.isEmpty() ? "薪资面议" : salary,
                    time,
                    jobUrl,
                    "贸易/进出口",
                    ""));
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * evaluateJavascript returns a JSON-encoded string:
     * the value is wrapped in quotes, with backslash-n, backslash-t,
     * and unicode escape sequences. We decode it back to plain HTML.
     */
    private String unquoteJs(String jsVal) {
        if (jsVal == null) return "";
        // Remove surrounding quotes if present
        if (jsVal.startsWith("\"") && jsVal.endsWith("\"")) {
            jsVal = jsVal.substring(1, jsVal.length() - 1);
        }
        // Unescape common sequences
        return jsVal
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .replaceAll("[\\\\]u[0-9a-fA-F]{4}", "?"); // unicode escapes -> placeholder
    }

    private void destroyWv(WebView wv) {
        if (wv == null) return;
        try { wv.stopLoading(); wv.setWebViewClient(null); wv.destroy(); }
        catch (Exception ignored) {}
    }
}
