package com.ledger.app.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 云端账本 REST 客户端。
 * 端点与网页版一致：{endpoint}/.cloud/database/rest/{table}
 * 认证：x-wb-webapp-access-key: <publishableKey> + Authorization: Bearer <access_token>
 */
public class CloudApi {
    static final String ENDPOINT = "https://ledger-sync-83297.app.workbuddy.host";
    static final String PUBKEY = "wbpk_iHfijba3qimoavp0MC3hLp_II5e4K8jMP8aO9CUarDoDp67lV1UW2PZ";
    static final String AUTH_PAGE = ENDPOINT + "/app-auth.html?sharecode=6EZ2DX7ZTO";
    static final String OWNER = "ledger-owner";
    private static final String PREFS = "ledger_native";
    private static final String KEY_TOKEN = "access_token";
    private static final String KEY_USER = "user_name";
    private static final String KEY_UID = "user_uid";

    /** 本地体验模式令牌：只在内存中演示，绝不写云端 */
    public static final String DEMO_TOKEN = "demo-local-session";
    private static final List<Record> DEMO_RECORDS = new ArrayList<>();
    static {
        DEMO_RECORDS.add(demo("2026-11-24", "餐饮", "午餐", -38.50, "微信"));
        DEMO_RECORDS.add(demo("2026-11-23", "日常消费", "超市采购", -126.80, "支付宝"));
        DEMO_RECORDS.add(demo("2026-11-22", "十一月工资", "工资到账", 8500.00, "银行卡"));
        DEMO_RECORDS.add(demo("2026-11-21", "给小美女", "礼物", -200.00, "微信"));
        DEMO_RECORDS.add(demo("2026-11-20", "固定支出", "房租", -1800.00, "银行卡"));
    }
    private static Record demo(String date, String category, String item, double amount, String account) {
        Record r = new Record();
        r.recKey = Record.newKey();
        r.date = date;
        r.category = category;
        r.item = item;
        r.amount = amount;
        r.account = account;
        r.period = "十一月工资";
        return r;
    }

    private final Context ctx;
    private final OkHttpClient http;
    private final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public CloudApi(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ---------- 会话 ----------
    public String getToken() {
        return prefs().getString(KEY_TOKEN, "");
    }
    public String getUserName() {
        return prefs().getString(KEY_USER, "");
    }
    public boolean isLoggedIn() {
        String t = getToken();
        return t != null && t.length() > 0;
    }
    public boolean isDemo() {
        return DEMO_TOKEN.equals(getToken());
    }
    public void saveSession(String token, String userName) {
        prefs().edit().putString(KEY_TOKEN, token).putString(KEY_USER, userName).apply();
    }
    /** 手机号登录：同时保存 uid，作为数据归属 owner_id */
    public void saveSession(String token, String userName, String uid) {
        prefs().edit().putString(KEY_TOKEN, token).putString(KEY_USER, userName)
                .putString(KEY_UID, uid == null ? "" : uid).apply();
    }
    public String getUid() {
        return prefs().getString(KEY_UID, "");
    }
    /** 当前数据归属：手机号用户用自己的 uid；历史兼容回退到 ledger-owner */
    public String getOwnerId() {
        String uid = getUid();
        return uid.isEmpty() ? OWNER : uid;
    }
    public void logout() {
        prefs().edit().remove(KEY_TOKEN).remove(KEY_USER).remove(KEY_UID).apply();
    }
    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 请求 ----------
    private Request.Builder authed(String url) {
        return new Request.Builder()
                .url(url)
                .header("x-wb-webapp-access-key", PUBKEY)
                .header("Authorization", "Bearer " + getToken())
                .header("Accept", "application/json");
    }

    private String exec(Request req) throws IOException {
        try (Response r = http.newCall(req).execute()) {
            ResponseBody body = r.body();
            String text = body == null ? "" : body.string();
            if (!r.isSuccessful()) {
                throw new IOException("HTTP " + r.code() + " " + text);
            }
            return text;
        }
    }

    /** 读取全部流水（未删除），按日期倒序 */
    public List<Record> listRecords() throws Exception {
        if (isDemo()) {
            List<Record> copy = new ArrayList<>();
            for (Record r : DEMO_RECORDS) copy.add(r);
            return copy;
        }
        String url = ENDPOINT + "/.cloud/database/rest/ledger_records?select=*"
                + "&owner_id=eq." + getOwnerId()
                + "&deleted=eq.false"
                + "&order=rec_date.desc";
        String text = exec(authed(url).get().build());
        JSONArray arr = new JSONArray(text);
        List<Record> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            out.add(Record.fromJson(arr.getJSONObject(i)));
        }
        return out;
    }

    /** 新增或更新一条流水（按 owner_id+rec_key 冲突合并） */
    public void upsertRecord(Record rec) throws Exception {
        if (isDemo()) {
            if (rec.recKey == null || rec.recKey.isEmpty()) rec.recKey = Record.newKey();
            DEMO_RECORDS.add(rec);
            return;
        }
        String url = ENDPOINT + "/.cloud/database/rest/ledger_records?on_conflict=owner_id,rec_key";
        RequestBody rb = RequestBody.create(rec.toJson(getOwnerId()).toString(), JSON);
        Request req = authed(url).post(rb)
                .header("Prefer", "resolution=merge-duplicates,return=representation,missing=default")
                .build();
        exec(req);
    }

    /** 软删除：把 deleted 置 true */
    public void softDelete(String recKey) throws Exception {
        if (isDemo()) {
            DEMO_RECORDS.removeIf(r -> recKey.equals(r.recKey));
            return;
        }
        String url = ENDPOINT + "/.cloud/database/rest/ledger_records?rec_key=eq." + recKey
                + "&owner_id=eq." + getOwnerId();
        JSONObject obj = new JSONObject();
        obj.put("deleted", true);
        obj.put("updated_at", Record.nowIso());
        RequestBody rb = RequestBody.create(obj.toString(), JSON);
        Request req = authed(url).patch(rb)
                .header("Prefer", "return=representation")
                .build();
        exec(req);
    }
}
