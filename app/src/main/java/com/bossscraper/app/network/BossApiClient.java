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

/**
 * Boss直聘 API 客户端
 * 登录后自动携带 Cookie，获取真实招聘数据；未登录时返回演示数据。
 */
public class BossApiClient {

    private static final String TAG = "BossApiClient";

    private static final String[] FOREIGN_TRADE_KEYWORDS = {
            "外贸", "外贸业务员", "国际贸易", "外贸跟单", "跨境电商",
            "外贸销售", "进出口", "外贸经理", "跨境贸易", "海外销售"
    };

    private static final String BASE_URL =
            "https://www.zhipin.com/wapi/zpgeek/search/joblist.json";

    public static final String[][] CITY_CODES = {
            {"全国", "100010000"},
            {"北京", "101010100"},
            {"上海", "101020100"},
            {"广州", "101280100"},
            {"深圳", "101280600"},
            {"杭州", "101210100"},
            {"成都", "101270100"},
            {"武汉", "101200100"},
            {"南京", "101190100"},
            {"西安", "101110100"},
            {"苏州", "101190400"},
            {"宁波", "101210400"},
            {"青岛", "101120200"},
            {"天津", "101030100"},
            {"重庆", "101040100"},
            {"厦门", "101230200"},
            {"郑州", "101180100"},
            {"福州", "101230100"},
            {"长沙", "101250100"},
            {"东莞", "101281600"},
            {"义乌", "101210600"},
    };

    private final OkHttpClient httpClient;
    private final Context context;

    public BossApiClient(Context context) {
        this.context = context.getApplicationContext();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    public interface FetchCallback {
        void onSuccess(List<JobItem> jobs, boolean isRealData);
        void onError(String errorMsg);
    }

    /** 读取已保存的 Cookie */
    private String getSavedCookie() {
        SharedPreferences prefs = context.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE);
        return prefs.getString("cookie", null);
    }

    public boolean isLoggedIn() {
        String cookie = getSavedCookie();
        return cookie != null && cookie.contains("wt2");
    }

    public void logout() {
        context.getSharedPreferences("boss_prefs", Context.MODE_PRIVATE)
               .edit().clear().apply();
    }

    public void fetchForeignTradeJobs(String cityCode, FetchCallback callback) {
        String cookie = getSavedCookie();
        if (cookie != null && cookie.contains("wt2")) {
            // 已登录：真实请求
            fetchKeyword(FOREIGN_TRADE_KEYWORDS[0], cityCode, cookie,
                    new ArrayList<>(), 0, callback);
        } else {
            // 未登录：直接返回演示数据
            callback.onSuccess(generateDemoData(cityCode), false);
        }
    }

    private void fetchKeyword(String keyword, String cityCode, String cookie,
                               List<JobItem> accumulated, int keywordIndex,
                               FetchCallback callback) {
        String url = BASE_URL
                + "?scene=1"
                + "&query=" + keyword
                + "&city=" + cityCode
                + "&experience=&jobType=&salary=&page=1"
                + "&pageSize=20"
                + "&publishTime=1";

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "https://www.zhipin.com/web/geek/job?query=" + keyword
                        + "&city=" + cityCode)
                .header("Cookie", cookie)
                .header("X-Requested-With", "XMLHttpRequest")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed: " + e.getMessage());
                if (accumulated.isEmpty()) {
                    callback.onError("网络请求失败：" + e.getMessage());
                } else {
                    callback.onSuccess(accumulated, true);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                response.close();

                if (code == 401 || code == 403) {
                    // Cookie 已过期
                    logout();
                    callback.onError("登录已过期，请重新扫码登录");
                    return;
                }

                if (!response.isSuccessful()) {
                    Log.w(TAG, "HTTP " + code);
                    if (accumulated.isEmpty()) {
                        callback.onSuccess(generateDemoData(cityCode), false);
                    } else {
                        callback.onSuccess(accumulated, true);
                    }
                    return;
                }

                try {
                    List<JobItem> parsed = parseResponse(body, cityCode);
                    accumulated.addAll(parsed);
                    Log.d(TAG, "Parsed " + parsed.size() + " jobs for: " + keyword);
                } catch (Exception e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                }

                int nextIndex = keywordIndex + 1;
                if (nextIndex < 3 && nextIndex < FOREIGN_TRADE_KEYWORDS.length) {
                    fetchKeyword(FOREIGN_TRADE_KEYWORDS[nextIndex], cityCode, cookie,
                            accumulated, nextIndex, callback);
                } else {
                    if (accumulated.isEmpty()) {
                        callback.onSuccess(generateDemoData(cityCode), false);
                    } else {
                        callback.onSuccess(accumulated, true);
                    }
                }
            }
        });
    }

    private List<JobItem> parseResponse(String json, String cityCode) {
        List<JobItem> result = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : -1;
            if (code != 0) return result;

            JsonObject zpData = root.getAsJsonObject("zpData");
            if (zpData == null) return result;

            JsonArray jobList = zpData.getAsJsonArray("jobList");
            if (jobList == null) return result;

            for (JsonElement el : jobList) {
                JsonObject job = el.getAsJsonObject();
                String jobTitle      = getStr(job, "jobName");
                String companyName   = getStr(job, "brandName");
                String salary        = getStr(job, "salaryDesc");
                String city          = getStr(job, "cityName");
                String area          = getStr(job, "areaDistrict");
                String businessDist  = getStr(job, "businessDistrict");
                String jobUrl        = "https://www.zhipin.com/job_detail/"
                                     + getStr(job, "encryptJobId") + ".html";
                String companyType   = getStr(job, "brandIndustry");
                String companyScale  = getStr(job, "brandScaleName");
                String address       = buildAddress(city, area, businessDist);

                result.add(new JobItem(jobTitle, companyName, address, city, area,
                        salary, "刚刚", jobUrl, companyType, companyScale));
            }
        } catch (Exception e) {
            Log.e(TAG, "parseResponse error: " + e.getMessage());
        }
        return result;
    }

    private String buildAddress(String city, String area, String business) {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isEmpty()) sb.append(city);
        if (area != null && !area.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(area);
        }
        if (business != null && !business.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(business);
        }
        return sb.length() > 0 ? sb.toString() : "地址未知";
    }

    private String getStr(JsonObject obj, String key) {
        try {
            JsonElement el = obj.get(key);
            if (el != null && !el.isJsonNull()) return el.getAsString();
        } catch (Exception ignored) {}
        return "";
    }

    private List<JobItem> generateDemoData(String cityCode) {
        List<JobItem> list = new ArrayList<>();
        String[][] demoJobs = {
                {"外贸业务员", "广州盛达进出口贸易有限公司", "广州", "天河区", "8-15K", "广州 · 天河区 · 天河北", "贸易/进出口", "100-499人"},
                {"外贸跟单", "深圳优贸供应链有限公司", "深圳", "福田区", "7-12K", "深圳 · 福田区 · 华强北", "供应链/物流", "50-99人"},
                {"国际贸易经理", "义乌汇通进出口有限公司", "义乌", "北苑街道", "15-25K", "义乌 · 北苑街道", "贸易/进出口", "500-999人"},
                {"跨境电商运营", "宁波跨境通电商科技有限公司", "宁波", "江北区", "10-18K", "宁波 · 江北区 · 洪塘", "电商/新零售", "100-499人"},
                {"外贸销售代表", "上海亚太国际贸易有限公司", "上海", "浦东新区", "12-20K", "上海 · 浦东新区 · 陆家嘴", "贸易/进出口", "200-499人"},
                {"进出口业务专员", "杭州联贸国际货运代理有限公司", "杭州", "下城区", "8-14K", "杭州 · 下城区", "货运/物流", "50-99人"},
                {"外贸业务经理", "青岛海盛贸易有限公司", "青岛", "市南区", "15-30K", "青岛 · 市南区 · 香港中路", "贸易/进出口", "100-499人"},
                {"外贸单证员", "厦门远洋货运代理有限公司", "厦门", "湖里区", "6-10K", "厦门 · 湖里区 · 象屿自贸区", "货运/物流", "50-99人"},
                {"跨境贸易专员", "东莞华创跨境电商有限公司", "东莞", "南城区", "8-13K", "东莞 · 南城区", "电商/新零售", "100-499人"},
                {"外贸采购专员", "苏州联创国际采购中心", "苏州", "工业园区", "9-15K", "苏州 · 工业园区 · 金鸡湖", "贸易/进出口", "500-999人"},
                {"海外市场开发", "广州凌云科技出口有限公司", "广州", "黄埔区", "15-25K", "广州 · 黄埔区", "科技/互联网", "200-499人"},
                {"外贸文员", "深圳鑫华贸易有限公司", "深圳", "宝安区", "5-8K", "深圳 · 宝安区 · 西乡", "贸易/进出口", "20-49人"},
                {"国际业务总监", "上海恒顺进出口集团", "上海", "静安区", "30-50K", "上海 · 静安区 · 南京西路", "贸易/进出口", "1000人以上"},
                {"外贸跟单主管", "宁波中欧贸易有限公司", "宁波", "鄞州区", "12-18K", "宁波 · 鄞州区 · 南部商务区", "贸易/进出口", "100-499人"},
                {"亚马逊运营", "杭州极速跨境电商有限公司", "杭州", "滨江区", "10-20K", "杭州 · 滨江区 · 网易科技园", "电商/新零售", "100-499人"},
        };
        String[] timeLabels = {"刚刚", "1分钟前", "2分钟前", "3分钟前", "4分钟前"};
        for (int i = 0; i < demoJobs.length; i++) {
            String[] d = demoJobs[i];
            list.add(new JobItem(d[0], d[1], d[5], d[2], d[3],
                    d[4], timeLabels[i % timeLabels.length],
                    "https://www.zhipin.com/", d[6], d[7]));
        }
        return list;
    }
}
