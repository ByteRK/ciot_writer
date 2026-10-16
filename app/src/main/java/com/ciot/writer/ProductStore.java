package com.ciot.writer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** 产品与连接配置的持久化。 */
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
    /** 当前使用中的产品下标。 */
    public int getSelected() { return preferences.getInt("selectedProduct", 0); }
    public void setSelected(int value) { preferences.edit().putInt("selectedProduct", value).apply(); }
    public List<Product> load() {
        List<Product> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("products", "[]"));
            for (int i=0; i<array.length(); i++) out.add(new Product(array.getJSONObject(i)));
        } catch (Exception ignored) { }
        if (out.isEmpty()) out.add(new Product());
        return out;
    }
    public void save(List<Product> products) {
        JSONArray array = new JSONArray();
        try { for (Product item : products) array.put(item.toJson()); }
        catch (JSONException ignored) { }
        preferences.edit().putString("products", array.toString()).apply();
    }
    public static List<Product> parseImport(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("version", -1) != 1 || root.optJSONArray("products") == null)
            throw new JSONException("产品文件格式无效（需要 version=1 和 products 数组）");
        JSONArray array = root.getJSONArray("products");
        List<Product> result = new ArrayList<>();
        for (int i=0; i<array.length(); i++) result.add(new Product(array.getJSONObject(i)));
        if (result.isEmpty()) throw new JSONException("产品文件中没有产品数据");
        return result;
    }
    /** 导出全部产品。 */
    public static String exportJson(List<Product> products) throws JSONException {
        JSONArray array = new JSONArray();
        for (Product product : products) array.put(product.toJson());
        return new JSONObject().put("version", 1).put("products", array).toString(2);
    }
}
