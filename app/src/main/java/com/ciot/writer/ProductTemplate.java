package com.ciot.writer;

import org.json.JSONException;
import org.json.JSONObject;

public class ProductTemplate {
    public String name = "新产品";
    public String pid = "";
    public String networkInterface = "eth0";
    public String targetPath = "/etc/license.txt";
    public String contentTemplate = "pid={{pid}}\ndid={{did}}\nlicense={{license}}\n";
    public int port = 23;
    public String username = "root";
    public String password = "";

    public ProductTemplate() {}

    public ProductTemplate(JSONObject json) throws JSONException {
        name = json.optString("name", name);
        pid = json.optString("pid", pid);
        networkInterface = json.optString("networkInterface", networkInterface);
        targetPath = json.optString("targetPath", targetPath);
        contentTemplate = json.optString("contentTemplate", contentTemplate.replace("\\n", "\n"));
        port = json.optInt("port", port);
        username = json.optString("username", username);
        password = json.optString("password", password);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("name", name).put("pid", pid)
                .put("networkInterface", networkInterface).put("targetPath", targetPath)
                .put("contentTemplate", contentTemplate).put("port", port)
                .put("username", username).put("password", password);
    }
}
