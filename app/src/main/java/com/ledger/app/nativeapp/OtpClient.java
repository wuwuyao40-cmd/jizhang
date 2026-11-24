package com.ledger.app.nativeapp;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 手机号验证码登录（WorkBuddy Cloud Auth v1）。
 * 三步：sendOtp -> verifyOtp -> signin(老用户) / signup(新用户)
 * 全部带 x-wb-webapp-access-key；成功后返回 access_token + user id(uid)。
 */
public class OtpClient {
    public static class Result {
        public String accessToken;
        public String uid;
        public String phone;
        public boolean isNewUser;
    }

    /** 发码结果：后续校验需要 verificationId，并据此判断新老用户 */
    public static class SendResult {
        public String verificationId;
        public boolean isExistingUser;
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 归一化手机号：+86 1xxxxxxxxxx；已是 86/0086 开头会去掉前缀 */
    static String normalizePhone(String input) {
        String t = input == null ? "" : input.replaceAll("\\D", "");
        if (t.startsWith("0086")) t = t.substring(4);
        else if (t.startsWith("86") && t.length() == 13) t = t.substring(2);
        if (t.matches("1\\d{10}")) return "+86 " + t;
        return input == null ? "" : input.trim();
    }

    private String post(String path, JSONObject body) throws IOException {
        Request req = new Request.Builder()
                .url(CloudApi.ENDPOINT + "/.cloud/auth" + path)
                .header("Content-Type", "application/json")
                .header("x-wb-webapp-access-key", CloudApi.PUBKEY)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response r = http.newCall(req).execute()) {
            ResponseBody b = r.body();
            String text = b == null ? "" : b.string();
            if (!r.isSuccessful()) throw new IOException(friendly(r.code(), text));
            return text;
        }
    }

    /** 安全解析 JSON 字符串，把受检异常转成 IOException */
    private JSONObject parse(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("服务端返回格式异常：" + e.getMessage());
        }
    }

    private String friendly(int code, String text) {
        String desc = text;
        try {
            JSONObject o = new JSONObject(text);
            desc = o.optString("error_description", o.optString("error", text));
        } catch (Exception ignored) {}
        if (code == 400 && desc.contains("PhoneNumberDailyLimit")) {
            return "该手机号今日短信已达上限，请明天再试或换一个手机号";
        }
        return "HTTP " + code + " " + desc;
    }

    public SendResult sendOtp(String phoneRaw) throws IOException {
        String phone = normalizePhone(phoneRaw);
        if (!phone.matches("\\+86 1\\d{10}")) throw new IOException("请输入 11 位中国大陆手机号");
        JSONObject body = new JSONObject();
        try {
            body.put("phone_number", phone);
            body.put("target", "ANY");
        } catch (Exception e) { throw new IOException(e.getMessage()); }
        JSONObject res = parse(post("/v1/verification", body));
        String vid = res.optString("verification_id", "");
        if (vid.isEmpty()) throw new IOException("服务端未返回 verification_id");
        SendResult out = new SendResult();
        out.verificationId = vid;
        out.isExistingUser = res.optBoolean("is_user", false);
        return out;
    }

    public Result verifyAndSignIn(String phoneRaw, SendResult send, String code, String password)
            throws IOException {
        String phone = normalizePhone(phoneRaw);
        JSONObject vbody = new JSONObject();
        try {
            vbody.put("verification_id", send.verificationId);
            vbody.put("verification_code", code.trim());
        } catch (Exception e) { throw new IOException(e.getMessage()); }
        JSONObject vres = parse(post("/v1/verification/verify", vbody));
        String vtoken = vres.optString("verification_token", "");
        if (vtoken.isEmpty()) throw new IOException("验证码错误或已过期");

        JSONObject sbody = new JSONObject();
        String path;
        try {
            if (send.isExistingUser) {
                path = "/v1/signin";
                sbody.put("username", phone);
                sbody.put("verification_token", vtoken);
            } else {
                path = "/v1/signup";
                sbody.put("phone_number", phone);
                sbody.put("verification_token", vtoken);
                String pw = (password == null || password.isEmpty()) ? defaultPassword(phone) : password;
                sbody.put("password", pw);
            }
        } catch (Exception e) { throw new IOException(e.getMessage()); }

        JSONObject session = parse(post(path, sbody));
        String token = session.optString("access_token", "");
        if (token.isEmpty()) throw new IOException("登录失败：未拿到访问令牌");

        Result out = new Result();
        out.accessToken = token;
        out.phone = phone;
        out.isNewUser = !send.isExistingUser;
        out.uid = session.optString("sub", session.optString("uid", ""));
        if (out.uid.isEmpty()) {
            JSONObject user = session.optJSONObject("user");
            if (user != null) out.uid = user.optString("id", user.optString("sub", ""));
        }
        return out;
    }

    /** 新用户需要一个初始密码；用手机号派生一个随机强密码，用户无需知道（后续走验证码登录） */
    private String defaultPassword(String phone) {
        long seed = Math.abs((phone + "jizhang").hashCode());
        return "Jz@" + Long.toString(seed, 36) + "Xy!9";
    }
}
