package bslsjdk.mcjavanpu;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class NpuServiceClient {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 38761;
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 3000;

    private static Socket socket;
    private static BufferedWriter out;
    private static BufferedReader in;

    private NpuServiceClient() {}

    private static synchronized void close() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
        out = null;
        in = null;
    }

    private static synchronized void connect() throws IOException {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        socket = s;
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    public static synchronized String request(String command) {
        if (command == null || command.isEmpty()) return "ERR EMPTY_COMMAND";
        String lastError = "unknown";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) connect();
                out.write(command);
                out.write('\n');
                out.flush();
                String line = in.readLine();
                if (line == null) throw new EOFException("service closed connection");
                return line;
            } catch (Throwable t) {
                close();
                String m = t.getMessage();
                lastError = t.getClass().getSimpleName() + (m == null ? "" : "(" + m + ")");
            }
        }
        return "ERR SERVICE_UNAVAILABLE " + lastError;
    }

    public static synchronized void closeAll() {
        close();
    }

    public static boolean isAvailable() {
        return request("PING").startsWith("PONG MCNPU/");
    }

    public static String status() {
        return request("STATUS");
    }

    public static String smoke() {
        return request("SMOKE");
    }

    public static String capabilities() {
        return request("CAPABILITIES");
    }

    /** Deterministic m x k times k x n matmul executed on the HTP service. */
    public static String matMul(int m, int k, int n) {
        return request("EXEC_MATMUL " + m + " " + k + " " + n);
    }

    public static String add(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length || a.length > 1024)
            return "ERR ADD_SIZE";
        StringBuilder sa = new StringBuilder(), sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return "ERR ADD_NON_FINITE";
            if (i > 0) {
                sa.append(',');
                sb.append(',');
            }
            sa.append(Float.toString(a[i]));
            sb.append(Float.toString(b[i]));
        }
        return request("EXEC_ADD " + sa + "|" + sb);
    }
}
