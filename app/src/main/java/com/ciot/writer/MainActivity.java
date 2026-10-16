package com.ciot.writer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：上方为终端式日志区（自动向上滚动），下方为固定高度的设备操作面板。
 * 服务器地址与产品模板管理已拆分到 {@link SettingsActivity}。
 */
public class MainActivity extends Activity {
    /** 终端最多保留的日志行数，超出后丢弃最早的记录，避免界面无限增长。 */
    private static final int MAX_LOG_LINES = 1000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Deque<String> logLines = new ArrayDeque<>();
    private TemplateStore store;
    private List<ProductTemplate> templates = new ArrayList<>();
    private Spinner templateSpinner;
    private TextView templateInfo, titleStatus;
    private EditText host;
    private CheckBox autoReboot;
    private ScrollView logScroll;
    private TextView logView;
    private boolean updatingSpinner = false;
    private boolean logStarted = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new TemplateStore(this);
        SystemInsets.enableEdgeToEdge(this);
        setContentView(R.layout.activity_main);

        templateSpinner = findViewById(R.id.templateSpinner);
        templateInfo = findViewById(R.id.templateInfo);
        titleStatus = findViewById(R.id.titleStatus);
        host = findViewById(R.id.host);
        autoReboot = findViewById(R.id.autoReboot);
        logScroll = findViewById(R.id.logScroll);
        logView = findViewById(R.id.logView);

        SystemInsets.avoidStatusBar(findViewById(R.id.topBar));
        SystemInsets.avoidBottomInset(findViewById(R.id.actionPanel));

        Button checkButton = findViewById(R.id.checkConnection);
        Button burnButton = findViewById(R.id.burn);
        Button syncButton = findViewById(R.id.syncRestart);
        Button copyLogButton = findViewById(R.id.copyLog);
        Button clearLogButton = findViewById(R.id.clearLog);
        Button settingsButton = findViewById(R.id.openSettings);

        host.setText(store.getHost());
        autoReboot.setChecked(store.getAutoReboot());

        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        checkButton.setOnClickListener(v -> runCheck());
        burnButton.setOnClickListener(v -> burn());
        syncButton.setOnClickListener(v -> syncRestart());
        copyLogButton.setOnClickListener(v -> copyLog());
        clearLogButton.setOnClickListener(v -> clearLog());
        templateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingSpinner) return;
                store.setSelected(position);
                updateTemplateInfo();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    /** 从设置页返回时重新加载模板，保证下拉列表与最新配置一致。 */
    @Override protected void onResume() {
        super.onResume();
        templates = store.load();
        refreshSpinner(store.getSelected());
    }

    @Override protected void onPause() {
        super.onPause();
        if (host != null) store.setHost(host.getText().toString());
        if (autoReboot != null) store.setAutoReboot(autoReboot.isChecked());
    }

    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }

    private void copyLog() {
        if (logLines.isEmpty()) { toast("暂无日志可复制"); return; }
        StringBuilder builder = new StringBuilder();
        for (String line : logLines) builder.append(line).append('\n');
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("CIOT Writer 日志", builder.toString()));
        toast("日志已复制到剪贴板");
    }

    private void clearLog() {
        logLines.clear();
        logStarted = true;
        if (logView != null) logView.setText("");
        append("日志已清空");
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void setStatus(String status) { if (titleStatus != null) titleStatus.setText(status); }

    private ProductTemplate currentTemplate() {
        int index = templateSpinner == null ? 0 : templateSpinner.getSelectedItemPosition();
        return templates.get(Math.max(0, Math.min(index, templates.size() - 1)));
    }

    private void refreshSpinner(int selected) {
        if (templateSpinner == null || templates.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (ProductTemplate t : templates) names.add(t.name + "  ·  PID " + (t.pid.isEmpty() ? "未设置" : t.pid));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        updatingSpinner = true;
        templateSpinner.setAdapter(adapter);
        int index = Math.max(0, Math.min(selected, templates.size() - 1));
        templateSpinner.setSelection(index);
        updatingSpinner = false;
        store.setSelected(index);
        updateTemplateInfo();
    }

    private void updateTemplateInfo() {
        if (templateInfo == null || templates.isEmpty()) return;
        ProductTemplate t = currentTemplate();
        templateInfo.setText("网卡 " + t.networkInterface + " · 写入 " + t.targetPath + " · Telnet " + t.port);
    }

    private void runCheck() {
        final ProductTemplate t = currentTemplate();
        final String device = host.getText().toString().trim();
        if (device.isEmpty()) { toast("请输入设备 IP"); return; }
        setStatus("连接测试中…");
        append("正在连接 " + device + ":" + t.port);
        worker.execute(() -> {
            try {
                WriterService.checkConnection(device, t);
                runOnUiThread(() -> { append("Telnet 登录成功"); setStatus("就绪"); });
            } catch (Exception e) {
                runOnUiThread(() -> { append("连接失败：" + e.getMessage()); setStatus("连接失败"); });
            }
        });
    }

    private void burn() {
        final ProductTemplate t = currentTemplate();
        final String device = host.getText().toString().trim();
        final String api = store.getServerUrl();
        if (device.isEmpty()) { toast("请输入设备 IP"); return; }
        if (api.isEmpty()) { toast("请先在设置中填写授权服务器地址"); return; }
        final boolean reboot = autoReboot.isChecked();
        setStatus("烧录中…");
        append("开始烧录：" + t.name + " · PID=" + t.pid);
        worker.execute(() -> {
            try {
                WriterService.Result r = WriterService.write(api, device, t, reboot, message -> runOnUiThread(() -> append(message)));
                runOnUiThread(() -> {
                    append("完成：PID=" + r.pid + "，DID=" + r.did + "，MAC=" + r.mac);
                    setStatus("烧录成功");
                    toast("授权文件写入成功");
                });
            } catch (Exception e) {
                runOnUiThread(() -> { append("烧录失败：" + e.getMessage()); setStatus("烧录失败"); toast("烧录失败"); });
            }
        });
    }

    private void syncRestart() {
        final ProductTemplate t = currentTemplate();
        final String device = host.getText().toString().trim();
        if (device.isEmpty()) { toast("请输入设备 IP"); return; }
        new AlertDialog.Builder(this).setTitle("同步并重启").setMessage("将连接 " + device + "，执行 sync 后重启设备。")
            .setNegativeButton("取消", null).setPositiveButton("执行", (d, w) -> {
                setStatus("同步并重启中…");
                append("正在同步并重启 " + device);
                worker.execute(() -> {
                    try {
                        WriterService.syncAndReboot(device, t);
                        runOnUiThread(() -> { append("已发送同步和重启命令"); setStatus("就绪"); });
                    } catch (Exception e) {
                        runOnUiThread(() -> { append("操作失败：" + e.getMessage()); setStatus("操作失败"); });
                    }
                });
            }).show();
    }

    /** 追加日志（自动滚动到底部），并按 {@link #MAX_LOG_LINES} 截断旧记录。 */
    private void append(String line) {
        if (line == null) return;
        runOnUiThread(() -> {
            if (logView == null) return;
            if (!logStarted) { logStarted = true; logLines.clear(); }
            for (String part : line.split("\n")) {
                logLines.addLast(part);
                while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
            }
            StringBuilder builder = new StringBuilder();
            for (String item : logLines) builder.append(item).append('\n');
            logView.setText(builder);
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
