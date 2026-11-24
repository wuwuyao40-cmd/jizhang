package com.ledger.app.nativeapp;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** 一条流水，字段与云端 ledger_records 表一致 */
public class Record {
    String recKey;
    String period;
    String category;
    String date;
    String item;
    double amount;
    String account;
    boolean reimbursement;
    String tag;
    String flag;
    String sourceCell;
    boolean deleted;
    String updatedAt;

    static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date());
    }

    static Record fromJson(JSONObject x) {
        Record r = new Record();
        r.recKey = x.optString("rec_key", "");
        r.period = x.optString("period", "");
        r.category = x.optString("category", "");
        String d = x.optString("rec_date", "");
        r.date = d.length() >= 10 ? d.substring(0, 10) : d;
        r.item = x.optString("item", "");
        r.amount = x.optDouble("amount", 0);
        r.account = x.optString("account", "");
        r.reimbursement = x.optBoolean("reimbursement", false);
        r.tag = x.optString("tag", "");
        r.flag = x.optString("flag", "");
        r.sourceCell = x.optString("source_cell", "");
        r.deleted = x.optBoolean("deleted", false);
        r.updatedAt = x.optString("updated_at", "");
        return r;
    }

    JSONObject toJson(String ownerId) {
        JSONObject o = new JSONObject();
        try {
            o.put("rec_key", recKey != null && !recKey.isEmpty() ? recKey : newKey());
            o.put("owner_id", ownerId);
            o.put("period", period);
            o.put("category", category);
            o.put("rec_date", date);
            o.put("item", item == null ? "" : item);
            o.put("amount", amount);
            o.put("account", account == null || account.isEmpty() ? "未记录" : account);
            o.put("reimbursement", reimbursement);
            o.put("tag", tag == null ? "" : tag);
            o.put("flag", flag == null ? "" : flag);
            o.put("source_cell", sourceCell == null ? "" : sourceCell);
            o.put("deleted", false);
            o.put("updated_at", nowIso());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    static String newKey() {
        return "native-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
