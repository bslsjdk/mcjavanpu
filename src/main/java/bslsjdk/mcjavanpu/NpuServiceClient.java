package bslsjdk.mcjavanpu;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class NpuServiceClient {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 38761;
    private static final String AUTH = "MCNPU/1";
    private static Socket socket;
    private static BufferedWriter out;
    private static BufferedReader in;

    private NpuServiceClient() {}

    private static void connect() throws IOException {
        close();
        socket = new Socket(InetAddress.getLoopbackAddress(), PORT);
        socket.setSoTimeout(3000);
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out.write("AUTH " + AUTH);
        out.write("\n");
        out.flush();
        String reply = in.readLine();
        if (!"OK AUTH".equals(reply)) {
            close();
            throw new IOException("MCNPU auth rejected: " + reply);
        }
    }

    public static synchronized String request(String command) {
        try {
            if (socket == null || socket.isClosed() || !socket.isConnected()) connect();
            out.write(command);
            out.write("\n");
            out.flush();
            String line = in.readLine();
            if (line == null) throw new EOFException("service closed IPC");
            if (command.equals("PING") && !line.startsWith("PONG MCNPU/")) {
                close();
                return "ERR INVALID_SERVICE_REPLY";
            }
            return line;
        } catch (IOException first) {
            close();
            try {
                connect();
                out.write(command);
                out.write("\n");
                out.flush();
                String line = in.readLine();
                if (line == null) return "ERR EMPTY_REPLY";
                if (command.equals("PING") && !line.startsWith("PONG MCNPU/"))
                    return "ERR INVALID_SERVICE_REPLY";
                return line;
            } catch (IOException second) {
                close();
                return "ERR SERVICE_UNAVAILABLE " + second.getClass().getSimpleName();
            } catch (RuntimeException second) {
                close();
                return "ERR SERVICE_RUNTIME " + second.getClass().getSimpleName();
            }
        } catch (RuntimeException first) {
            close();
            return "ERR SERVICE_RUNTIME " + first.getClass().getSimpleName();
        }
    }

    public static synchronized void close() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
        out = null;
        in = null;
    }

    public static boolean isAvailable() { return request("PING").startsWith("PONG MCNPU/"); }
    public static String status() { return request("STATUS"); }
    public static String smoke() { return request("SMOKE"); }
    public static String capabilities() { return request("CAPABILITIES"); }

    public static String add(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length || a.length > 1024)
            return "ERR ADD_SIZE";
        StringBuilder sa = new StringBuilder(), sb = new StringBuilder();
        for (int i=0;i<a.length;i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return "ERR ADD_NON_FINITE";
            if (i > 0) { sa.append(','); sb.append(','); }
            sa.append(Float.toString(a[i]));
            sb.append(Float.toString(b[i]));
        }
        return request("EXEC_ADD " + sa + "|" + sb);
    }
}
