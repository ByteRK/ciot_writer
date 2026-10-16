package com.ciot.writer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Small interactive Telnet client for common embedded Linux login shells. */
public class TelnetSession implements AutoCloseable {
    private final Socket socket = new Socket();
    private final InputStream input;
    private final OutputStream output;
    private static final Pattern LOGIN = Pattern.compile("(?im)(?:login|username)\\s*:");
    private static final Pattern PASS = Pattern.compile("(?im)password\\s*:");
    private static final Pattern PROMPT = Pattern.compile("(?m)(?:^|\\n)[^\\r\\n]{0,100}[#$>]\\s*$");

    public TelnetSession(String host, int port, String username, String password) throws Exception {
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(250);
        input = socket.getInputStream(); output = socket.getOutputStream();
        String first = readUntil("login");
        if (LOGIN.matcher(first).find()) {
            send(username);
            String second = readUntil("password or shell");
            if (PASS.matcher(second).find()) { send(password); readUntil("shell"); }
            else if (!PROMPT.matcher(second).find()) throw new Exception("无法识别 Telnet 登录提示");
        } else if (PASS.matcher(first).find()) {
            send(password); readUntil("shell");
        } else if (!PROMPT.matcher(first).find()) throw new Exception("未检测到 Telnet 登录提示或 Shell 提示符");
    }

    public synchronized String command(String command) throws Exception {
        send(command);
        return readUntil("shell");
    }

    public synchronized void commandWithoutWaiting(String command) throws Exception { send(command); }

    private void send(String value) throws Exception { output.write((value + "\r\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }

    private String readUntil(String phase) throws Exception {
        long end = System.currentTimeMillis() + (phase.equals("shell") ? 30000 : 10000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (System.currentTimeMillis() < end) {
            try {
                int b = input.read();
                if (b < 0) throw new Exception("Telnet 连接已关闭");
                if (b == 255) { negotiate(); continue; }
                if (b == 0) continue;
                bytes.write(b);
                String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
                if (phase.equals("login") && (LOGIN.matcher(text).find() || PASS.matcher(text).find() || PROMPT.matcher(text).find())) return text;
                if (phase.equals("password or shell") && (PASS.matcher(text).find() || PROMPT.matcher(text).find())) return text;
                if (phase.equals("shell") && PROMPT.matcher(text).find()) return text;
            } catch (java.net.SocketTimeoutException ignored) { }
        }
        throw new Exception("等待 Telnet " + phase + " 超时");
    }

    private void negotiate() throws Exception {
        int command = input.read(); if (command < 0) return;
        if (command == 255) return;
        if (command == 250) { // Skip IAC SB ... IAC SE subnegotiation payloads.
            int previous = -1;
            while (true) {
                int value = input.read(); if (value < 0) return;
                if (previous == 255 && value == 240) return;
                previous = value;
            }
        }
        if (command == 240 || command == 241 || command == 242 || command == 243 || command == 244 || command == 245 || command == 246 || command == 247 || command == 248 || command == 249) return;
        int option = input.read(); if (option < 0) return;
        // Refuse peer options (WONT/DONT), acknowledge with the matching negative verb.
        if (command == 251 || command == 252) output.write(new byte[]{(byte)255, (byte)254, (byte)option});
        else if (command == 253 || command == 254) output.write(new byte[]{(byte)255, (byte)252, (byte)option});
        output.flush();
    }

    @Override public void close() { try { socket.close(); } catch (Exception ignored) { } }
}
