package com.bossscraper.app.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.bossscraper.app.model.JobItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BossApiClient {

    private static final String TAG = "BossApiClient";

    private static final String[] KEYWORDS = {"外贸", "国际贸易", "跨境电商"};

    private static final String BASE_URL =
            "https://www.zhipin.com/wapi/zpgeek/search/joblist.json";

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

    private final OkHttpClient httpClient;
    private final Context context;

    public BossApiClient(Context context) {
        this.context = context.getApplicationContext();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    private String getSavedCookie() {
        return context.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
                      .getString("cookie", null);
    }

    public boolean isLoggedIn() {
        String c = getSavedCookie();
        return c != null && c.contains("wt2");
    }

    public void logout() {
        context.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
               .edit().clear().apply();
    }

    // Always try to fetch real data; only fall back to demo if completely blocked
    public void fetchForeignTradeJobs(String cityCode, FetchCallback callback) {
        String cookie = getSavedCookie();
        // Always attempt the real API — with or without login cookie
        // Boss 直聘的搜索 API 对未登录用户也会返回部分数据
        fetchKeyword(KEYWORDS[0], cityCode, cookie != null ? cookie : "",
                new ArrayList<>(), 0, callback);
    }

    private void fetchKeyword(String keyword, String cityCode, String cookie,
                              List<JobItem> accumulated, int keywordIndex,
                              FetchCallback callback) {
        String url = BASE_URL
                + "?scene=1"
                + "&query=" + encode(keyword)
                + "&city=" + cityCode
                + "&experience=&jobType=&salary=&page=1&pageSize=20&publishTime=1";

        Request req = new Request.Builder()
                .url(url)
                .get()
                // Full browser-like headers — required to avoid 403
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Referer",
                        "https://www.zhipin.com/web/geek/job?query=" + encode(keyword)
                        + "&city=" + cityCode)
                .header("Origin", "https://www.zhipin.com")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cookie", cookie)
                .build();

        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "onFailure: " + e.getMessage());
                if (accumulated.isEmpty()) {
                    callback.onError("网络请求失败：" + e.getMessage());
                } else {
                    callback.onSuccess(new ArrayList<>(accumulated), true);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                String body = "";
                try {
                    if (response.body() != null) body = response.body().string();
                } finally {
                    response.close();
                }

                Log.d(TAG, "HTTP " + code + " keyword=" + keyword
                        + " body_len=" + body.length());

                if (code == 401 || code == 403) {
                    Log.w(TAG, "Auth required, falling back");
                    finishOrNext(keywordIndex, keyword, cityCode, cookie,
                            accumulated, callback, true);
                    return;
                }

                if (code != 200) {
                    Log.w(TAG, "Non-200: " + code);
                    finishOrNext(keywordIndex, keyword, cityCode, cookie,
                            accumulated, callback, false);
                    return;
                }

                try {
                    List<JobItem> parsed = parseResponse(body);
                    Log.d(TAG, "Parsed " + parsed.size() + " jobs for " + keyword);
                    accumulated.addAll(parsed);
                } catch (Exception e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                }

                finishOrNext(keywordIndex, keyword, cityCode, cookie,
                        accumulated, callback, false);
            }
        });
    }

    private void finishOrNext(int keywordIndex, String keyword, String cityCode,
                              String cookie, List<JobItem> accumulated,
                              FetchCallback callback, boolean stop) {
        int next = keywordIndex + 1;
        if (!stop && next < KEYWORDS.length) {
            fetchKeyword(KEYWORDS[next], cityCode, cookie, accumulated, next, callback);
        } else {
            if (accumulated.isEmpty()) {
                // Truly blocked — return demo data
                callback.onSuccess(generateDemoData(cityCode), false);
            } else {
                callback.onSuccess(new ArrayList<>(accumulated), true);
            }
        }
    }

    private List<JobItem> parseResponse(String json) {
        List<JobItem> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : -1;
            if (code != 0) {
                Log.w(TAG, "API code=" + code
                        + " msg=" + (root.has("message") ? root.get("message") : ""));
                return result;
            }
            JsonObject zpData = root.has("zpData") ? root.getAsJsonObject("zpData") : null;
            if (zpData == null) return result;
            JsonArray jobList = zpData.has("jobList") ? zpData.getAsJsonArray("jobList") : null;
            if (jobList == null) return result;

            for (JsonElement el : jobList) {
                try {
                    JsonObject job = el.getAsJsonObject();
                    String city     = str(job, "cityName");
                    String area     = str(job, "areaDistrict");
                    String biz      = str(job, "businessDistrict");
                    String encId    = str(job, "encryptJobId");
                    String jobUrl   = encId.isEmpty() ? "https://www.zhipin.com"
                            : "https://www.zhipin.com/job_detail/" + encId + ".html";
                    result.add(new JobItem(
                            str(job, "jobName"),
                            str(job, "brandName"),
                            addr(city, area, biz),
                            city, area,
                            str(job, "salaryDesc"),
                            "刚刚",
                            jobUrl,
                            str(job, "brandIndustry"),
                            str(job, "brandScaleName")
                    ));
                } catch (Exception e) {
                    Log.w(TAG, "Skip job: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseResponse: " + e.getMessage());
        }
        return result;
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

    private List<JobItem> generateDemoData(String cityCode) {
        List<JobItem> list = new ArrayList<>();
        String[][] data = {
            {"外贸业务员",   "广州盛达进出口贸易有限公司","广州·天河区","广州","天河区","8-15K","贸易/进出口","100-499人"},
            {"外贸跟单",     "深圳优贸供应链有限公司",   "深圳·福田区","深圳","福田区","7-12K","供应链/物流","50-99人"},
            {"国际贸易经理", "义乌汇通进出口有限公司",   "义乌·北苑", "义乌","北苑", "15-25K","贸易/进出口","500-999人"},
            {"跨境电商运营", "宁波跨境通电商科技有限公司","宁波·江北区","宁波","江北区","10-18K","电商/新零售","100-499人"},
            {"外贸销售代表", "上海亚太国际贸易有限公司", "上海·浦东新区","上海","浦东新区","12-20K","贸易/进出口","200-499人"},
            {"外贸业务经理", "青岛海盛贸易有限公司",     "青岛·市南区","青岛","市南区","15-30K","贸易/进出口","100-499人"},
            {"外贸单证员",   "厦门远洋货运代理有限公司", "厦门·湖里区","厦门","湖里区","6-10K","货运/物流","50-99人"},
            {"跨境贸易专员", "东莞华创跨境电商有限公司", "东莞·南城区","东莞","南城区","8-13K","电商/新零售","100-499人"},
            {"外贸采购专员", "苏州联创国际采购中心",     "苏州·工业园区","苏州","工业园区","9-15K","贸易/进出口","500-999人"},
            {"海外市场开发", "广州凌云科技出口有限公司", "广州·黄埔区","广州","黄埔区","15-25K","科技/互联网","200-499人"},
        };
        String[] times = {"刚刚","1分钟前","2分钟前","3分钟前","4分钟前"};
        for (int i = 0; i < data.length; i++) {
            String[] d = data[i];
            list.add(new JobItem(d[0],d[1],d[2],d[3],d[4],
                    d[5],times[i%times.length],"https://www.zhipin.com/",d[6],d[7]));
        }
        return list;
    }
}
