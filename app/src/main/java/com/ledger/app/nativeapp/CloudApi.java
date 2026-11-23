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
    public void saveSession(String token, String userName) {
        prefs().edit().putString(KEY_TOKEN, token).putString(KEY_USER, userName).apply();
    }
    public void logout() {
        prefs().edit().remove(KEY_TOKEN).remove(KEY_USER).apply();
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
        String url = ENDPOINT + "/.cloud/database/rest/ledger_records?select=*"
                + "&owner_id=eq." + OWNER
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
        String url = ENDPOINT + "/.cloud/database/rest/ledger_records?on_conflict=owner_id,rec_key";
        RequestBody rb = RequestBody.create(rec.toJson().toString(), JSON);
        Request req = authed(url).post(rb)
                .header("Prefer", "resolution=merge-duplicates,return=representation,missing=default")
                .build();
        exec(req);
    }

    /** 软删除：把 deleted 置 true */
    public void softDelete(String recKey) throws Exception {
        String url = ENDPOINT + "/.cloud/database/rest/ledger_records?rec_key=eq." + recKey
                + "&owner_id=eq." + OWNER;
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
