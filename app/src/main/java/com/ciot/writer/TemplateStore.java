package com.ciot.writer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class TemplateStore {
    private final SharedPreferences preferences;
    public TemplateStore(Context context) { preferences = context.getSharedPreferences("ciot-writer", Context.MODE_PRIVATE); }
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
    public List<ProductTemplate> load() {
        List<ProductTemplate> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("templates", "[]"));
            for (int i=0; i<array.length(); i++) out.add(new ProductTemplate(array.getJSONObject(i)));
        } catch (Exception ignored) { }
        if (out.isEmpty()) out.add(new ProductTemplate());
        return out;
    }
    public void save(List<ProductTemplate> templates) {
        JSONArray array = new JSONArray();
        try { for (ProductTemplate item : templates) array.put(item.toJson()); }
        catch (JSONException ignored) { }
        preferences.edit().putString("templates", array.toString()).apply();
    }
    public static List<ProductTemplate> parseImport(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("version", -1) != 1 || !(root.opt("templates") instanceof JSONArray))
            throw new JSONException("模板文件格式无效（需要 version=1 和 templates 数组）");
        JSONArray array = root.getJSONArray("templates");
        List<ProductTemplate> result = new ArrayList<>();
        for (int i=0; i<array.length(); i++) result.add(new ProductTemplate(array.getJSONObject(i)));
        if (result.isEmpty()) throw new JSONException("模板文件中没有产品模板");
        return result;
    }
    public static String exportJson(List<ProductTemplate> templates) throws JSONException {
        JSONArray array = new JSONArray();
        for (ProductTemplate template : templates) array.put(template.toJson());
        return new JSONObject().put("version", 1).put("templates", array).toString(2);
    }
}
