package com.ciot.writer;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个产品：既包含产品标识（名称、PID），也包含针对它的授权文件烧录配置
 * （网卡名、写入路径、Telnet 参数、文件内容模板）。
 */
public class Product {
    public String name = "新产品";
    public String pid = "";
    public String networkInterface = "eth0";
    public String targetPath = "/etc/license.txt";
    /** 写入设备文件的内容模板，需包含 {{pid}}/{{did}}/{{license}} 占位符。 */
    public String contentTemplate = "pid={{pid}}\ndid={{did}}\nlicense={{license}}\n";
    public int port = 23;
    public String username = "root";
    public String password = "";

    public Product() {}

    public Product(JSONObject json) throws JSONException {
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
