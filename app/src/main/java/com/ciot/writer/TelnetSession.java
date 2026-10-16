package com.ciot.writer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small interactive Telnet client for common embedded Linux login shells. */
public class TelnetSession implements AutoCloseable {
    private final Socket socket = new Socket();
    private final InputStream input;
    private final OutputStream output;
    private String pendingCommand;
    private static final Pattern LOGIN = Pattern.compile("(?im)(?:login|username)\\s*:\\s*$");
    private static final Pattern PASS = Pattern.compile("(?im)password\\s*:\\s*$");
    // '>' 不能是箭头（-> 或 =>）的一部分，banner 中常出现此类字符。
    private static final Pattern PROMPT = Pattern.compile("(?m)(?:^|\\n)[^\\r\\n]{0,120}(?:[#$]|(?<![=\\-])>)\\s*$");
    private static final Pattern AUTH_FAIL = Pattern.compile("(?i)incorrect|authentication failed|login incorrect");

    private static final String PHASE_LOGIN = "login";
    private static final String PHASE_PASSWORD_OR_SHELL = "password or shell";
    private static final String PHASE_LOGIN_RESULT = "login result";
    private static final String PHASE_SHELL = "shell";

    /** 命令执行结果：已剥离命令回显与结束标记的输出，以及命令退出码。 */
    public static final class Result {
        public final String output;
        public final int exitCode;
        Result(String output, int exitCode) { this.output = output; this.exitCode = exitCode; }
    }

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

    /**
     * 执行命令并在其后追加唯一结束标记（{@code echo "<token>$?"}），通过标记后紧跟的数字（退出码）
     * 判断命令是否执行完毕。不再依赖通用 Shell 提示符匹配，从根本上避免命令回显中的
     * {@code $}、{@code >}、{@code #} 被误判为提示符而导致输出被提前截断（回显中的 {@code $?}
     * 后紧跟的是 {@code $} 而非数字，不会触发结束判定）。
     */
    public synchronized Result run(String command) throws Exception {
        String token = "__CIOT_" + Long.toHexString(System.nanoTime()) + "__";
        Pattern done = Pattern.compile(Pattern.quote(token) + "(\\d+)");
        send(command + "; echo \"" + token + "$?\"");
        String raw = readText(text -> done.matcher(text).find(), 30000, "命令输出");
        Matcher matcher = done.matcher(raw);
        int exitCode = -1, end = raw.length();
        while (matcher.find()) { exitCode = Integer.parseInt(matcher.group(1)); end = matcher.start(); }
        return new Result(raw.substring(0, end), exitCode);
    }

    /** 发送重启命令后等待设备断开连接（或短暂超时），确保命令已被设备处理而非随 socket 关闭而丢失。 */
    public synchronized void reboot() throws Exception {
        send("reboot");
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                if (input.read() < 0) return; // 设备已断开，重启已执行
            } catch (java.net.SocketTimeoutException ignored) { }
        }
    }

    private void send(String value) throws Exception { pendingCommand = value; output.write((value + "\r\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }

    /**
     * 剔除命令回显：设备会把我们发送的命令原文回显到终端。由于本类逐字节读取并实时匹配提示符，
     * 回显中形如 {@code $}、{@code >} 的字符（例如 {@code echo "EXIT:$?"} 中的 {@code $}）
     * 会在数据尚未接收完整时被误判为 Shell 提示符，导致命令结果被提前截断。
     * 因此先剥离回显（或判断仍处于回显接收中），再对剩余内容做提示符匹配。
     */
    private String stripEcho(String text) {
        String command = pendingCommand;
        if (command == null || command.isEmpty()) return text;
        if (text.startsWith(command)) return text.substring(command.length());
        if (command.startsWith(text)) return ""; // 仍在接收命令回显，尚未开始输出
        return text;
    }

    private String readUntil(String phase) throws Exception {
        long timeout = PHASE_SHELL.equals(phase) ? 30000 : 10000;
        String raw = readText(text -> matchesPhase(phase, stripEcho(text)), timeout, phase);
        return stripEcho(raw);
    }

    private boolean matchesPhase(String phase, String visible) {
        if (PHASE_LOGIN.equals(phase)) return LOGIN.matcher(visible).find() || PASS.matcher(visible).find() || PROMPT.matcher(visible).find() || AUTH_FAIL.matcher(visible).find();
        if (PHASE_PASSWORD_OR_SHELL.equals(phase)) return PASS.matcher(visible).find() || PROMPT.matcher(visible).find() || AUTH_FAIL.matcher(visible).find();
        if (PHASE_LOGIN_RESULT.equals(phase)) return PROMPT.matcher(visible).find() || AUTH_FAIL.matcher(visible).find();
        return PROMPT.matcher(visible).find();
    }

    private interface Checker { boolean done(String text); }

    /** 逐字节读取并处理 Telnet/ANSI 控制序列，累积为可打印文本直到 {@code checker} 判定完成。 */
    private String readText(Checker checker, long timeoutMillis, String label) throws Exception {
        long end = System.currentTimeMillis() + timeoutMillis;
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
                if (checker.done(text)) return text;
            } catch (java.net.SocketTimeoutException ignored) { }
        }
        throw new Exception("等待 Telnet " + label + " 超时");
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
