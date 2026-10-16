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

/** 设置页：维护授权服务器地址与产品信息（含烧录配置），避免主界面过长。 */
public class SettingsActivity extends Activity {
    private static final int IMPORT_JSON = 3101, EXPORT_JSON = 3102;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ProductStore store;
    private List<Product> products;
    private EditText serverUrl;
    private EditText productFilter;
    private ListView productList;
    private TextView productCount;
    private ProductAdapter productAdapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ProductStore(this);
        products = store.load();
        SystemInsets.enableEdgeToEdge(this);
        setContentView(R.layout.activity_settings);

        serverUrl = findViewById(R.id.serverUrl);
        productFilter = findViewById(R.id.productFilter);
        productList = findViewById(R.id.productList);
        productCount = findViewById(R.id.productCount);
        TextView productEmpty = findViewById(R.id.productEmpty);
        CheckBox fetchDialog = findViewById(R.id.fetchDialog);

        SystemInsets.avoidStatusBar(findViewById(R.id.topBar));
        SystemInsets.avoidBottomInset(findViewById(R.id.settingsScroll));

        Button closeButton = findViewById(R.id.closeSettings);
        Button saveServerButton = findViewById(R.id.saveServer);
        Button testServerButton = findViewById(R.id.testServer);
        Button addButton = findViewById(R.id.addProduct);
        Button importButton = findViewById(R.id.importProducts);
        Button exportButton = findViewById(R.id.exportProducts);

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
        addButton.setOnClickListener(v -> editProduct(null));
        importButton.setOnClickListener(v -> pickImportFile());
        exportButton.setOnClickListener(v -> pickExportFile());
        productFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderList(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        productAdapter = new ProductAdapter(this, new ProductAdapter.Listener() {
            @Override public void onSelect(int index) { store.setSelected(index); renderList(); }
            @Override public void onEdit(Product product) { editProduct(product); }
            @Override public void onDelete(int index) { confirmDelete(index); }
        }, products);
        productList.setAdapter(productAdapter);
        productList.setEmptyView(productEmpty);
        renderList();
    }

    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    /** 当前使用中的产品下标（越界自动收敛）。 */
    private int currentIndex() {
        if (products.isEmpty()) return 0;
        return Math.max(0, Math.min(store.getSelected(), products.size() - 1));
    }

    /** 按筛选关键字刷新列表（适配器只渲染可见行），并同步“当前使用”标记与数量提示。 */
    private void renderList() {
        if (productAdapter == null) return;
        int current = currentIndex();
        if (current != store.getSelected()) store.setSelected(current);
        String query = productFilter == null ? "" : productFilter.getText().toString().trim().toLowerCase();
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            Product product = products.get(i);
            if (query.isEmpty() || product.name.toLowerCase().contains(query) || product.pid.toLowerCase().contains(query)) shown.add(i);
        }
        if (productCount != null) {
            productCount.setText(query.isEmpty()
                    ? getString(R.string.product_count, products.size())
                    : getString(R.string.product_count_filtered, shown.size(), products.size()));
        }
        productAdapter.submit(shown, current);
    }

    /** 删除指定产品（带确认）；列表清空时自动补一个默认产品，保证始终有可选项。 */
    private void confirmDelete(final int index) {
        if (index < 0 || index >= products.size()) return;
        final Product product = products.get(index);
        new AlertDialog.Builder(this).setTitle("删除产品")
            .setMessage("确定删除“" + product.name + "”？此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (d, w) -> {
                products.remove(index);
                if (products.isEmpty()) products.add(new Product());
                store.save(products);
                int selected = store.getSelected();
                if (index < selected) selected--;
                store.setSelected(Math.max(0, Math.min(selected, products.size() - 1)));
                renderList();
                toast("产品已删除");
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

    private void editProduct(Product original) {
        final boolean editing = original != null;
        final Product product = editing ? original : new Product();
        View form = getLayoutInflater().inflate(R.layout.dialog_product, null);
        EditText name = form.findViewById(R.id.fieldName), pid = form.findViewById(R.id.fieldPid), nic = form.findViewById(R.id.fieldNic);
        EditText path = form.findViewById(R.id.fieldPath), port = form.findViewById(R.id.fieldPort);
        EditText username = form.findViewById(R.id.fieldUsername), password = form.findViewById(R.id.fieldPassword);
        EditText content = form.findViewById(R.id.fieldContent);
        name.setText(product.name); pid.setText(product.pid); nic.setText(product.networkInterface); path.setText(product.targetPath);
        port.setText(String.valueOf(product.port)); username.setText(product.username); password.setText(product.password); content.setText(product.contentTemplate);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(editing ? "编辑产品信息" : "新增产品信息").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                String p = pid.getText().toString().trim(), n = name.getText().toString().trim(), iface = nic.getText().toString().trim(), target = path.getText().toString().trim(), body = content.getText().toString();
                int telnetPort; try { telnetPort = Integer.parseInt(port.getText().toString()); } catch (Exception ex) { toast("Telnet 端口无效"); return; }
                if (n.isEmpty() || p.isEmpty() || !iface.matches("[A-Za-z0-9_.:-]{1,64}") || !target.startsWith("/") || telnetPort < 1 || telnetPort > 65535) { toast("请检查名称、PID、网卡、绝对路径和端口"); return; }
                for (String slot : new String[]{"{{pid}}", "{{did}}", "{{license}}"}) if (!body.contains(slot)) { toast("内容模板缺少 " + slot); return; }
                product.name = n; product.pid = p; product.networkInterface = iface; product.targetPath = target; product.port = telnetPort;
                product.username = username.getText().toString(); product.password = password.getText().toString(); product.contentTemplate = body;
                if (!editing) { products.add(product); store.setSelected(products.size() - 1); }
                store.save(products);
                renderList();
                toast("产品已保存");
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
        intent.putExtra(Intent.EXTRA_TITLE, "ciot-writer-products.json");
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
                    final List<Product> imported = ProductStore.parseImport(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    new AlertDialog.Builder(this).setTitle("替换产品")
                        .setMessage("导入文件包含 " + imported.size() + " 个产品，将替换当前全部产品。是否继续？")
                        .setNegativeButton("取消", null).setPositiveButton("导入", (dialog, which) -> {
                            products.clear();
                            products.addAll(imported);
                            store.save(products);
                            store.setSelected(0);
                            renderList();
                            toast("产品导入完成：" + products.size() + " 个");
                        }).show();
                }
            } else if (requestCode == EXPORT_JSON) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    out.write(ProductStore.exportJson(products).getBytes(StandardCharsets.UTF_8));
                    toast("已导出全部 " + products.size() + " 个产品");
                }
            }
        } catch (Exception e) {
            toast("文件处理失败：" + e.getMessage());
        }
    }
}
