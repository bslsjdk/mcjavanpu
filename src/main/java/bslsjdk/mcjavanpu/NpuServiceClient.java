package bslsjdk.mcjavanpu;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;

public final class NpuServiceClient {
    private static final String SOCKET_NAME = "mcnpu_ipc_v1";
    private static LocalSocket socket;
    private static BufferedWriter out;
    private static BufferedReader in;

    private NpuServiceClient() {}

    private static void connect() throws IOException {
        close();
        socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
        socket.setSoTimeout(3000);
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }

    public static synchronized String request(String command) {
        try {
            if (socket == null || !socket.isConnected()) connect();
            out.write(command);
            out.write("\n");
            out.flush();
            String line = in.readLine();
            if (line == null) throw new EOFException("service closed IPC");
            return line;
        } catch (IOException first) {
            close();
            try {
                connect();
                out.write(command);
                out.write("\n");
                out.flush();
                String line = in.readLine();
                return line == null ? "ERR EMPTY_REPLY" : line;
            } catch (IOException second) {
                close();
                return "ERR SERVICE_UNAVAILABLE " + second.getClass().getSimpleName();
            }
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
