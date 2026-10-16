package com.ciot.writer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 产品与连接配置的持久化。
 *
 * <p>SharedPreferences 键名沿用历史值（"templates" / "selectedTemplate"）：
 * 它们只是内部实现细节，改名会让老用户已保存的产品静默丢失，故保持不变。
 */
public class ProductStore {
    private final SharedPreferences preferences;
    public ProductStore(Context context) { preferences = context.getSharedPreferences("ciot-writer", Context.MODE_PRIVATE); }
    public String getServerUrl() { return preferences.getString("serverUrl", ""); }
    public void setServerUrl(String value) { preferences.edit().putString("serverUrl", value.trim()).apply(); }
    public String getHost() { return preferences.getString("host", ""); }
    public void setHost(String value) { preferences.edit().putString("host", value.trim()).apply(); }
    public boolean getAutoReboot() { return preferences.getBoolean("autoReboot", false); }
    public void setAutoReboot(boolean value) { preferences.edit().putBoolean("autoReboot", value).apply(); }
    /** 抓取文件后是否弹出内容预览窗口，默认开启。 */
    public boolean getShowFetchDialog() { return preferences.getBoolean("showFetchDialog", true); }
    public void setShowFetchDialog(boolean value) { preferences.edit().putBoolean("showFetchDialog", value).apply(); }
    public int getSelected() { return preferences.getInt("selectedTemplate", 0); }
    public void setSelected(int value) { preferences.edit().putInt("selectedTemplate", value).apply(); }
    public List<Product> load() {
        List<Product> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("templates", "[]"));
            for (int i=0; i<array.length(); i++) out.add(new Product(array.getJSONObject(i)));
        } catch (Exception ignored) { }
        if (out.isEmpty()) out.add(new Product());
        return out;
    }
    public void save(List<Product> products) {
        JSONArray array = new JSONArray();
        try { for (Product item : products) array.put(item.toJson()); }
        catch (JSONException ignored) { }
        preferences.edit().putString("templates", array.toString()).apply();
    }
    /** 解析导入文件；同时兼容早期导出的 "templates" 容器字段。 */
    public static List<Product> parseImport(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray array = root.optJSONArray("products");
        if (array == null) array = root.optJSONArray("templates");
        if (root.optInt("version", -1) != 1 || array == null)
            throw new JSONException("产品文件格式无效（需要 version=1 和 products 数组）");
        List<Product> result = new ArrayList<>();
        for (int i=0; i<array.length(); i++) result.add(new Product(array.getJSONObject(i)));
        if (result.isEmpty()) throw new JSONException("产品文件中没有产品数据");
        return result;
    }
    /** 导出全部产品，容器字段为 products。 */
    public static String exportJson(List<Product> products) throws JSONException {
        JSONArray array = new JSONArray();
        for (Product product : products) array.put(product.toJson());
        return new JSONObject().put("version", 1).put("products", array).toString(2);
    }
}
