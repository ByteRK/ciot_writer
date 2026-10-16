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
    private static final Pattern LOGIN = Pattern.compile("(?im)(?:login|username)\\s*:\\s*$");
    private static final Pattern PASS = Pattern.compile("(?im)password\\s*:\\s*$");
    // '>' 不能是箭头（-> 或 =>）的一部分，banner 中常出现此类字符。
    private static final Pattern PROMPT = Pattern.compile("(?m)(?:^|\\n)[^\\r\\n]{0,120}(?:[#$]|(?<![=\\-])>)\\s*$");
    private static final Pattern AUTH_FAIL = Pattern.compile("(?i)incorrect|authentication failed|login incorrect");

    private static final String PHASE_LOGIN = "login";
    private static final String PHASE_PASSWORD_OR_SHELL = "password or shell";
    private static final String PHASE_LOGIN_RESULT = "login result";
    private static final String PHASE_SHELL = "shell";

    public TelnetSession(String host, int port, String username, String password) throws Exception {
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(250);
        input = socket.getInputStream(); output = socket.getOutputStream();
        String first = readUntil(PHASE_LOGIN);
        if (AUTH_FAIL.matcher(first).find()) throw new Exception("Telnet 用户名或密码错误");
        if (LOGIN.matcher(first).find()) {
            send(username);
            String second = readUntil(PHASE_PASSWORD_OR_SHELL);
            if (AUTH_FAIL.matcher(second).find()) throw new Exception("Telnet 用户名或密码错误");
            if (PASS.matcher(second).find()) {
                send(password);
                String loggedIn = readUntil(PHASE_LOGIN_RESULT);
                if (AUTH_FAIL.matcher(loggedIn).find()) throw new Exception("Telnet 用户名或密码错误");
            } else if (!PROMPT.matcher(second).find()) throw new Exception("无法识别 Telnet 登录提示");
        } else if (PASS.matcher(first).find()) {
            send(password);
            String loggedIn = readUntil(PHASE_LOGIN_RESULT);
            if (AUTH_FAIL.matcher(loggedIn).find()) throw new Exception("Telnet 用户名或密码错误");
        } else if (!PROMPT.matcher(first).find()) throw new Exception("未检测到 Telnet 登录提示或 Shell 提示符");
    }

    public synchronized String command(String command) throws Exception {
        send(command);
        return readUntil(PHASE_SHELL);
    }

    public synchronized void commandWithoutWaiting(String command) throws Exception { send(command); }

    private void send(String value) throws Exception { output.write((value + "\r\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }

    private String readUntil(String phase) throws Exception {
        long end = System.currentTimeMillis() + (PHASE_SHELL.equals(phase) ? 30000 : 10000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int ansiState = 0; // 0=普通文本, 1=刚读到 ESC, 2=CSI 序列中
        while (System.currentTimeMillis() < end) {
            if (Thread.currentThread().isInterrupted()) throw new Exception("操作已取消");
            try {
                int b = input.read();
                if (b < 0) throw new Exception("Telnet 连接已关闭");
                if (ansiState == 2) { // 等待 CSI 结束字节 0x40-0x7e
                    if (b >= 0x40 && b <= 0x7e) ansiState = 0;
                    continue;
                }
                if (ansiState == 1) { // 判断是 CSI 还是两字节转义
                    ansiState = (b == 0x5b) ? 2 : 0;
                    continue;
                }
                if (b == 255) { // IAC
                    int command = input.read();
                    if (command < 0) throw new Exception("Telnet 连接已关闭");
                    if (command == 255) { bytes.write(255); continue; } // 转义 IAC -> 字面量
                    negotiate(command);
                    continue;
                }
                if (b == 0x1b) { ansiState = 1; continue; } // ESC 开始 ANSI 序列
                if (b == 0x09 || b == 0x0a || b == 0x0d || (b >= 0x20 && b != 0x7f)) bytes.write(b);
                String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
                if (PHASE_LOGIN.equals(phase) && (LOGIN.matcher(text).find() || PASS.matcher(text).find() || PROMPT.matcher(text).find() || AUTH_FAIL.matcher(text).find())) return text;
                if (PHASE_PASSWORD_OR_SHELL.equals(phase) && (PASS.matcher(text).find() || PROMPT.matcher(text).find() || AUTH_FAIL.matcher(text).find())) return text;
                if (PHASE_LOGIN_RESULT.equals(phase) && (PROMPT.matcher(text).find() || AUTH_FAIL.matcher(text).find())) return text;
                if (PHASE_SHELL.equals(phase) && PROMPT.matcher(text).find()) return text;
            } catch (java.net.SocketTimeoutException ignored) { }
        }
        throw new Exception("等待 Telnet " + phase + " 超时");
    }

    private void negotiate(int command) throws Exception {
        if (command == 250) { // 跳过 IAC SB ... IAC SE 子协商内容。
            int previous = -1;
            while (true) {
                int value = input.read(); if (value < 0) return;
                if (previous == 255 && value == 240) return;
                previous = value;
            }
        }
        if (command >= 240 && command <= 249) return; // SE/NOP/DM/BRK/IP/AO/AYT/EC/EL/GA
        int option = input.read(); if (option < 0) return;
        // 拒绝对端选项（WONT/DONT），用对应的否定应答。
        if (command == 251 || command == 252) output.write(new byte[]{(byte)255, (byte)254, (byte)option});
        else if (command == 253 || command == 254) output.write(new byte[]{(byte)255, (byte)252, (byte)option});
        output.flush();
    }

    @Override public void close() { try { socket.close(); } catch (Exception ignored) { } }
}
