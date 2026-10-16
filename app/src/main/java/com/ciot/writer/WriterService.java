package com.ciot.writer;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WriterService {
    public interface Progress { void log(String message); }
    public static class Result { public String pid, mac, did, license, content; }

    public static void checkConnection(String host, ProductTemplate t) throws Exception {
        validate(host, t);
        try (TelnetSession session = new TelnetSession(host, t.port, t.username, t.password)) { }
    }

    public static void checkServer(String serverUrl) throws Exception {
        String base = serverUrl.trim().replaceAll("/+$", "");
        if (!base.matches("(?i)^https?://.+")) throw new Exception("服务器地址请以 http:// 或 https:// 开头");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/device/authorize").openConnection();
        connection.setRequestMethod("OPTIONS");
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        try {
            int status = connection.getResponseCode();
            if (status != 204) throw new Exception(status == 404 ? "服务器可访问，但未找到授权接口" : "授权接口检测失败（HTTP " + status + "）");
        } finally { connection.disconnect(); }
    }

    public static Result write(String serverUrl, String host, ProductTemplate t, boolean reboot, Progress progress) throws Exception {
        validate(host, t);
        String mac;
        try (TelnetSession session = new TelnetSession(host, t.port, t.username, t.password)) {
            progress.log("Telnet 登录成功，读取 " + t.networkInterface + " 网卡 MAC");
            String output = session.command("cat /sys/class/net/" + t.networkInterface + "/address");
            Matcher matcher = Pattern.compile("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}").matcher(output);
            if (!matcher.find()) throw new Exception("无法读取有效 MAC 地址：" + t.networkInterface);
            mac = matcher.group().replace(':', '-').toUpperCase();
            progress.log("设备 MAC：" + mac + "，正在向授权服务器申请");
            JSONObject data = requestAuthorization(serverUrl, t.pid, mac);
            Result result = new Result();
            result.pid = data.getString("pid"); result.mac = data.optString("mac", mac);
            result.did = data.getString("display_code");
            result.license = data.getJSONObject("payload").optString("license", "");
            if (result.license.isEmpty()) throw new Exception("服务器返回数据缺少 payload.license");
            result.content = t.contentTemplate.replace("{{pid}}", result.pid)
                    .replace("{{did}}", result.did).replace("{{license}}", result.license);
            try {
                String encoded = Base64.getEncoder().encodeToString(result.content.getBytes(StandardCharsets.UTF_8));
                session.command("printf '%s' '" + encoded + "' | base64 -d > " + shellQuote(t.targetPath));
                progress.log("授权文件已写入 " + t.targetPath + "，正在同步");
                session.command("sync");
            } catch (Exception writeFailure) {
                throw new Exception("授权码已分配并绑定（DID="+result.did+"），但设备文件写入或同步失败："+writeFailure.getMessage()+"。授权绑定仍保留，请处理后重试", writeFailure);
            }
            if (reboot) {
                progress.log("同步完成，正在重启设备");
                session.commandWithoutWaiting("reboot");
            }
            return result;
        }
    }

    public static void syncAndReboot(String host, ProductTemplate t) throws Exception {
        try (TelnetSession session = new TelnetSession(host, t.port, t.username, t.password)) {
            session.command("sync");
            session.commandWithoutWaiting("reboot");
        }
    }

    private static JSONObject requestAuthorization(String serverUrl, String pid, String mac) throws Exception {
        String base = serverUrl.trim().replaceAll("/+$", "");
        if (!base.matches("(?i)^https?://.+")) throw new Exception("服务器地址请以 http:// 或 https:// 开头");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/device/authorize").openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setDoOutput(true);
        byte[] body = new JSONObject().put("pid", pid).put("mac", mac).toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) { byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);stream.close(); }
        String response = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        connection.disconnect();
        JSONObject root = new JSONObject(response);
        if (status != 200 || !root.optBoolean("success", false))
            throw new Exception(root.optString("message", "授权服务器请求失败（HTTP " + status + "）"));
        return root.getJSONObject("data");
    }

    private static void validate(String host, ProductTemplate t) throws Exception {
        if (host == null || !host.trim().matches("[A-Za-z0-9][A-Za-z0-9.:-]*")) throw new Exception("请输入有效的设备 IP 或主机名");
        if (t.pid == null || t.pid.trim().isEmpty()) throw new Exception("模板 PID 不能为空");
        if (t.networkInterface == null || !t.networkInterface.matches("[A-Za-z0-9_.:-]{1,64}")) throw new Exception("模板网卡名格式不正确");
        if (t.targetPath == null || !t.targetPath.startsWith("/") || t.targetPath.indexOf('\r') >= 0 || t.targetPath.indexOf('\n') >= 0 || t.targetPath.indexOf('\0') >= 0) throw new Exception("设备文件路径必须是有效的绝对路径");
        if (t.port < 1 || t.port > 65535) throw new Exception("Telnet 端口必须在 1-65535 之间");
        if (t.username == null || t.password == null || t.username.indexOf('\r') >= 0 || t.username.indexOf('\n') >= 0 || t.password.indexOf('\r') >= 0 || t.password.indexOf('\n') >= 0) throw new Exception("Telnet 用户名或密码不能包含换行符");
        for(String slot:new String[]{"{{pid}}","{{did}}","{{license}}"}) if(t.contentTemplate==null || !t.contentTemplate.contains(slot)) throw new Exception("文件模板缺少槽位："+slot);
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
