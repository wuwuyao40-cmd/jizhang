package com.ledger.app.nativeapp;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 记账本原生主界面：流水 / 统计 / 设置 */
public class MainActivity extends AppCompatActivity {
    private static final String[] CATS = {"日常消费", "餐饮", "给小美女", "固定支出"};
    private static final String[] PERIODS = {"十月工资", "十一月工资"};

    private CloudApi api;
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final List<Record> records = new ArrayList<>();

    private View loggedOut, loggedIn;
    private TextView userTag, syncTag;
    private RecyclerView recycler;
    private RecAdapter adapter;
    private SwipeRefreshLayout refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        api = new CloudApi(this);

        loggedOut = findViewById(R.id.loggedOut);
        loggedIn = findViewById(R.id.loggedIn);
        userTag = findViewById(R.id.userTag);
        syncTag = findViewById(R.id.syncTag);
        recycler = findViewById(R.id.recycler);
        refresh = findViewById(R.id.refresh);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        adapter = new RecAdapter();
        recycler.setAdapter(adapter);

        findViewById(R.id.loginBtn).setOnClickListener(v -> startLogin());
        findViewById(R.id.manualBtn).setOnClickListener(v -> showManualTokenDialog());
        findViewById(R.id.demoBtn).setOnClickListener(v -> {
            api.saveSession(CloudApi.DEMO_TOKEN, "体验用户");
            updateAuthUi();
            Toast.makeText(this, "已进入本地体验模式，数据不会写入云端", Toast.LENGTH_LONG).show();
        });
        findViewById(R.id.logoutBtn).setOnClickListener(v -> {
            api.logout();
            records.clear();
            adapter.notifyDataSetChanged();
            updateAuthUi();
        });
        findViewById(R.id.addBtn).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.statsBtn).setOnClickListener(v -> showStats());
        refresh.setOnRefreshListener(this::load);

        updateAuthUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** 处理 jizhang://auth?payload=<base64> 回调 */
    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        if (!"jizhang".equals(data.getScheme())) return;
        String payload = data.getQueryParameter("payload");
        if (TextUtils.isEmpty(payload)) return;
        try {
            byte[] raw = Base64.decode(payload, Base64.DEFAULT);
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String token = o.optString("access_token", "");
            String name = o.optString("name", "");
            if (!TextUtils.isEmpty(token)) {
                api.saveSession(token, name);
                updateAuthUi();
                load();
                Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "登录失败：令牌为空", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "登录解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startLogin() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(CloudApi.AUTH_PAGE));
            startActivity(i);
            Toast.makeText(this, "请在浏览器中完成登录，成功后会自动返回", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 手动粘贴令牌（微信无法自动跳回 App 时的兜底） */
    private void showManualTokenDialog() {
        EditText et = new EditText(this);
        et.setHint("粘贴桥接页显示的访问令牌");
        et.setSingleLine(false);
        et.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("手动输入令牌")
                .setMessage("在浏览器完成登录后，复制页面上显示的令牌，粘贴到下方。")
                .setView(et)
                .setPositiveButton("登录", (d, w) -> {
                    String token = et.getText().toString().trim();
                    if (token.isEmpty()) {
                        Toast.makeText(this, "令牌为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    api.saveSession(token, "");
                    updateAuthUi();
                    Toast.makeText(this, "已保存令牌", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateAuthUi() {
        boolean in = api.isLoggedIn();
        loggedOut.setVisibility(in ? View.GONE : View.VISIBLE);
        loggedIn.setVisibility(in ? View.VISIBLE : View.GONE);
        if (in) {
            userTag.setText(api.isDemo() ? "👤 本地体验模式" : "👤 " + (api.getUserName().isEmpty() ? "已登录" : api.getUserName()));
            load();
        }
    }

    private void load() {
        if (!api.isLoggedIn()) return;
        if (!api.isDemo()) runUi(() -> syncTag.setText("同步中…"));
        exec.execute(() -> {
            try {
                List<Record> list = api.listRecords();
                runUi(() -> {
                    records.clear();
                    records.addAll(list);
                    adapter.notifyDataSetChanged();
                    syncTag.setText(api.isDemo() ? "本地 " + records.size() + " 笔" : "☁ " + records.size() + " 笔");
                    refresh.setRefreshing(false);
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runUi(() -> {
                    syncTag.setText("同步失败");
                    refresh.setRefreshing(false);
                    Toast.makeText(this, "加载失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showAddDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add, null);
        Spinner spCat = v.findViewById(R.id.spCategory);
        Spinner spPeriod = v.findViewById(R.id.spPeriod);
        EditText etDate = v.findViewById(R.id.etDate);
        EditText etItem = v.findViewById(R.id.etItem);
        EditText etAmount = v.findViewById(R.id.etAmount);
        EditText etAccount = v.findViewById(R.id.etAccount);

        spCat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, CATS));
        spPeriod.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PERIODS));
        etDate.setText(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()));

        new AlertDialog.Builder(this)
                .setTitle("新增流水")
                .setView(v)
                .setPositiveButton("保存", (d, w) -> {
                    String amountStr = etAmount.getText().toString().trim();
                    if (TextUtils.isEmpty(amountStr)) {
                        Toast.makeText(this, "请输入金额", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Record r = new Record();
                    r.category = (String) spCat.getSelectedItem();
                    r.period = (String) spPeriod.getSelectedItem();
                    r.date = etDate.getText().toString().trim();
                    r.item = etItem.getText().toString().trim();
                    try { r.amount = Double.parseDouble(amountStr); } catch (Exception ignored) {}
                    r.account = etAccount.getText().toString().trim();
                    if (r.account.isEmpty()) r.account = "未记录";
                    exec.execute(() -> {
                        try {
                            api.upsertRecord(r);
                            runUi(() -> { Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show(); load(); });
                        } catch (Exception e) {
                            runUi(() -> Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showStats() {
        Map<String, Double> sum = new LinkedHashMap<>();
        double total = 0;
        for (Record r : records) {
            double a = Math.abs(r.amount);
            sum.put(r.category, sum.getOrDefault(r.category, 0.0) + a);
            total += a;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(records.size()).append(" 笔，合计 ¥").append(String.format(Locale.US, "%.2f", total)).append("\n\n");
        for (Map.Entry<String, Double> e : sum.entrySet()) {
            sb.append(e.getKey()).append("：¥").append(String.format(Locale.US, "%.2f", e.getValue())).append("\n");
        }
        new AlertDialog.Builder(this).setTitle("分类统计").setMessage(sb.toString()).setPositiveButton("关闭", null).show();
    }

    private void runUi(Runnable r) { runOnUiThread(r); }

    // ---------- 列表适配器 ----------
    private class RecAdapter extends RecyclerView.Adapter<RecAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            TextView date, item, cat, amount;
            VH(View v) {
                super(v);
                date = v.findViewById(R.id.rowDate);
                item = v.findViewById(R.id.rowItem);
                cat = v.findViewById(R.id.rowCat);
                amount = v.findViewById(R.id.rowAmount);
            }
        }
        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.row_record, p, false));
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            Record r = records.get(pos);
            h.date.setText(r.date);
            h.item.setText(TextUtils.isEmpty(r.item) ? "(无项目)" : r.item);
            h.cat.setText(r.category);
            h.amount.setText(String.format(Locale.US, "¥%.2f", r.amount));
            h.amount.setTextColor(r.amount < 0 ? 0xFFD93025 : 0xFF1A9D63);
            h.itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除这条记录？")
                        .setMessage(r.date + "  " + r.item + "  ¥" + r.amount)
                        .setPositiveButton("删除", (d, w) -> exec.execute(() -> {
                            try {
                                api.softDelete(r.recKey);
                                runUi(() -> { Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show(); load(); });
                            } catch (Exception e) {
                                runUi(() -> Toast.makeText(MainActivity.this, "删除失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        }))
                        .setNegativeButton("取消", null).show();
                return true;
            });
        }
        @Override public int getItemCount() { return records.size(); }
    }
}
