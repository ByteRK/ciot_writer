package com.ciot.writer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 设置页：维护授权服务器地址与产品模板，避免主界面过长。 */
public class SettingsActivity extends Activity {
    private static final int IMPORT_JSON = 3101, EXPORT_JSON = 3102;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TemplateStore store;
    private List<ProductTemplate> templates;
    private EditText serverUrl;
    private EditText templateFilter;
    private ListView templateList;
    private TextView templateCount;
    private TemplateAdapter templateAdapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new TemplateStore(this);
        templates = store.load();
        SystemInsets.enableEdgeToEdge(this);
        setContentView(R.layout.activity_settings);

        serverUrl = findViewById(R.id.serverUrl);
        templateFilter = findViewById(R.id.templateFilter);
        templateList = findViewById(R.id.templateList);
        templateCount = findViewById(R.id.templateCount);
        TextView templateEmpty = findViewById(R.id.templateEmpty);
        CheckBox fetchDialog = findViewById(R.id.fetchDialog);

        SystemInsets.avoidStatusBar(findViewById(R.id.topBar));
        SystemInsets.avoidBottomInset(findViewById(R.id.settingsScroll));

        Button closeButton = findViewById(R.id.closeSettings);
        Button saveServerButton = findViewById(R.id.saveServer);
        Button testServerButton = findViewById(R.id.testServer);
        Button addButton = findViewById(R.id.addTemplate);
        Button importButton = findViewById(R.id.importTemplates);
        Button exportButton = findViewById(R.id.exportTemplates);

        serverUrl.setText(store.getServerUrl());

        TextView aboutInfo = findViewById(R.id.aboutInfo);
        aboutInfo.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                + "\n" + getString(R.string.about_build_time, BuildConfig.BUILD_TIME)
                + "\n" + getString(R.string.about_copyright));

        fetchDialog.setChecked(store.getShowFetchDialog());
        fetchDialog.setOnCheckedChangeListener((button, checked) -> store.setShowFetchDialog(checked));

        closeButton.setOnClickListener(v -> finish());
        saveServerButton.setOnClickListener(v -> { store.setServerUrl(serverUrl.getText().toString()); toast("服务器地址已保存"); });
        testServerButton.setOnClickListener(v -> checkServer(testServerButton));
        addButton.setOnClickListener(v -> editTemplate(null));
        importButton.setOnClickListener(v -> pickImportFile());
        exportButton.setOnClickListener(v -> pickExportFile());
        templateFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderList(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        templateAdapter = new TemplateAdapter(this, new TemplateAdapter.Listener() {
            @Override public void onSelect(int index) { store.setSelected(index); renderList(); }
            @Override public void onEdit(ProductTemplate template) { editTemplate(template); }
            @Override public void onDelete(int index) { confirmDelete(index); }
        }, templates);
        templateList.setAdapter(templateAdapter);
        templateList.setEmptyView(templateEmpty);
        renderList();
    }

    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    /** 当前使用中的模板下标（越界自动收敛）。 */
    private int currentIndex() {
        if (templates.isEmpty()) return 0;
        return Math.max(0, Math.min(store.getSelected(), templates.size() - 1));
    }

    /** 按筛选关键字刷新列表（适配器只渲染可见行），并同步“当前使用”标记与数量提示。 */
    private void renderList() {
        if (templateAdapter == null) return;
        int current = currentIndex();
        if (current != store.getSelected()) store.setSelected(current);
        String query = templateFilter == null ? "" : templateFilter.getText().toString().trim().toLowerCase();
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < templates.size(); i++) {
            ProductTemplate t = templates.get(i);
            if (query.isEmpty() || t.name.toLowerCase().contains(query) || t.pid.toLowerCase().contains(query)) shown.add(i);
        }
        if (templateCount != null) {
            templateCount.setText(query.isEmpty()
                    ? getString(R.string.template_count, templates.size())
                    : getString(R.string.template_count_filtered, shown.size(), templates.size()));
        }
        templateAdapter.submit(shown, current);
    }

    /** 删除指定产品（带确认），列表清空时自动补一个默认模板。 */
    private void confirmDelete(final int index) {
        if (index < 0 || index >= templates.size()) return;
        final ProductTemplate t = templates.get(index);
        new AlertDialog.Builder(this).setTitle("删除产品")
            .setMessage("确定删除“" + t.name + "”？此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (d, w) -> {
                templates.remove(index);
                if (templates.isEmpty()) templates.add(new ProductTemplate());
                store.save(templates);
                int selected = store.getSelected();
                if (index < selected) selected--;
                store.setSelected(Math.max(0, Math.min(selected, templates.size() - 1)));
                renderList();
                toast("模板已删除");
            }).show();
    }

    private void checkServer(Button button) {
        final String api = serverUrl.getText().toString().trim();
        if (api.isEmpty()) { toast("请输入授权服务器地址"); return; }
        store.setServerUrl(api);
        button.setEnabled(false);
        toast("正在检测授权服务器…");
        worker.execute(() -> {
            try {
                WriterService.checkServer(api);
                runOnUiThread(() -> { toast("服务器连接成功"); button.setEnabled(true); });
            } catch (Exception e) {
                runOnUiThread(() -> { toast("服务器连接失败：" + e.getMessage()); button.setEnabled(true); });
            }
        });
    }

    private void editTemplate(ProductTemplate original) {
        final boolean editing = original != null;
        final ProductTemplate t = editing ? original : new ProductTemplate();
        View form = getLayoutInflater().inflate(R.layout.dialog_template, null);
        EditText name = form.findViewById(R.id.fieldName), pid = form.findViewById(R.id.fieldPid), nic = form.findViewById(R.id.fieldNic);
        EditText path = form.findViewById(R.id.fieldPath), port = form.findViewById(R.id.fieldPort);
        EditText username = form.findViewById(R.id.fieldUsername), password = form.findViewById(R.id.fieldPassword);
        EditText content = form.findViewById(R.id.fieldContent);
        name.setText(t.name); pid.setText(t.pid); nic.setText(t.networkInterface); path.setText(t.targetPath);
        port.setText(String.valueOf(t.port)); username.setText(t.username); password.setText(t.password); content.setText(t.contentTemplate);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(editing ? "编辑产品模板" : "新增产品模板").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                String p = pid.getText().toString().trim(), n = name.getText().toString().trim(), iface = nic.getText().toString().trim(), target = path.getText().toString().trim(), body = content.getText().toString();
                int telnetPort; try { telnetPort = Integer.parseInt(port.getText().toString()); } catch (Exception ex) { toast("Telnet 端口无效"); return; }
                if (n.isEmpty() || p.isEmpty() || !iface.matches("[A-Za-z0-9_.:-]{1,64}") || !target.startsWith("/") || telnetPort < 1 || telnetPort > 65535) { toast("请检查名称、PID、网卡、绝对路径和端口"); return; }
                for (String slot : new String[]{"{{pid}}", "{{did}}", "{{license}}"}) if (!body.contains(slot)) { toast("文件模板缺少 " + slot); return; }
                t.name = n; t.pid = p; t.networkInterface = iface; t.targetPath = target; t.port = telnetPort;
                t.username = username.getText().toString(); t.password = password.getText().toString(); t.contentTemplate = body;
                if (!editing) { templates.add(t); store.setSelected(templates.size() - 1); }
                store.save(templates);
                renderList();
                toast("模板已保存");
            }).create();
        // 弹窗为浮动窗口，部分系统仅支持平移；显式声明 adjustResize 以便在支持的机型上收缩并滚动到当前字段。
        Window window = dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private void pickImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, IMPORT_JSON);
    }

    private void pickExportFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "ciot-writer-templates.json");
        startActivityForResult(intent, EXPORT_JSON);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (requestCode == IMPORT_JSON) {
                try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    final List<ProductTemplate> imported = TemplateStore.parseImport(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    new AlertDialog.Builder(this).setTitle("替换产品模板")
                        .setMessage("导入文件包含 " + imported.size() + " 个模板，将替换当前全部模板。是否继续？")
                        .setNegativeButton("取消", null).setPositiveButton("导入", (dialog, which) -> {
                            templates.clear();
                            templates.addAll(imported);
                            store.save(templates);
                            store.setSelected(0);
                            renderList();
                            toast("模板导入完成：" + templates.size() + " 个");
                        }).show();
                }
            } else if (requestCode == EXPORT_JSON) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    out.write(TemplateStore.exportJson(templates).getBytes(StandardCharsets.UTF_8));
                    toast("已导出全部 " + templates.size() + " 个模板");
                }
            }
        } catch (Exception e) {
            toast("文件处理失败：" + e.getMessage());
        }
    }
}
