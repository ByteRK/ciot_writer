package com.ciot.writer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    /** 将抓取到的设备文件另存到本地的请求码。 */
    private static final int EXPORT_FILE = 3103;
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
    /** 最近一次抓取到的设备文件内容与默认文件名，供另存到本地时使用。 */
    private String fetchedContent, fetchedFileName;

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
        Button fetchButton = findViewById(R.id.fetchFile);
        Button copyLogButton = findViewById(R.id.copyLog);
        Button clearLogButton = findViewById(R.id.clearLog);
        Button settingsButton = findViewById(R.id.openSettings);

        host.setText(store.getHost());
        autoReboot.setChecked(store.getAutoReboot());

        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        checkButton.setOnClickListener(v -> runCheck());
        burnButton.setOnClickListener(v -> burn());
        syncButton.setOnClickListener(v -> syncRestart());
        fetchButton.setOnClickListener(v -> fetchFile());
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
        // templateInfo.setText(t.networkInterface + " · Telnet " + t.port + " · " + t.targetPath);
        templateInfo.setText("Telnet " + t.port + " · " + t.networkInterface + " · " + t.targetPath);
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

    /** 连接设备读取模板中配置的授权文件，输出到日志并提供复制、另存选项。 */
    private void fetchFile() {
        final ProductTemplate t = currentTemplate();
        final String device = host.getText().toString().trim();
        if (device.isEmpty()) { toast("请输入设备 IP"); return; }
        setStatus("抓取文件中…");
        append("正在读取设备文件 " + t.targetPath);
        worker.execute(() -> {
            try {
                String content = WriterService.readFile(device, t);
                runOnUiThread(() -> {
                    if (content.trim().isEmpty()) {
                        append("设备文件为空：" + t.targetPath);
                        setStatus("文件为空");
                        toast("设备文件内容为空");
                        return;
                    }
                    append("---- " + t.targetPath + " ----");
                    append(content);
                    append("---- 共 " + content.length() + " 字符 ----");
                    setStatus("抓取成功");
                    if (store.getShowFetchDialog()) showFetchedContent(t, content);
                    else toast("已输出到日志：" + t.targetPath);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { append("抓取失败：" + e.getMessage()); setStatus("抓取失败"); toast("抓取失败"); });
            }
        });
    }

    /** 以可滚动、可选择的弹窗展示抓取到的文件内容，避免长内容撑破对话框。 */
    private void showFetchedContent(ProductTemplate t, String content) {
        fetchedContent = content;
        fetchedFileName = t.targetPath.substring(t.targetPath.lastIndexOf('/') + 1);
        if (fetchedFileName.isEmpty()) fetchedFileName = "license.txt";
        float density = getResources().getDisplayMetrics().density;
        TextView body = new TextView(this);
        body.setText(content);
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(12);
        body.setTextColor(getColor(R.color.colorDark));
        int padding = (int) (16 * density);
        body.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * density)));
        new AlertDialog.Builder(this).setTitle("已抓取 " + fetchedFileName)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .setNeutralButton(R.string.btn_save_file, (d, w) -> saveFetchedFile())
            .setPositiveButton(R.string.btn_copy_content, (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("CIOT Writer 授权文件", content));
                toast("文件内容已复制");
            }).show();
    }

    private void saveFetchedFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, fetchedFileName);
        startActivityForResult(intent, EXPORT_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (fetchedContent == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            out.write(fetchedContent.getBytes(StandardCharsets.UTF_8));
            append("授权文件已保存：" + fetchedFileName);
            toast("授权文件已保存");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
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
