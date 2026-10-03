package bslsjdk.mcjavanpu;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Persistent text+binary IPC client for the MCNPU service.
 *
 * The control plane is UTF-8 text lines. The data plane is raw bytes, so this
 * class deliberately does NOT use BufferedReader/BufferedWriter: buffered
 * readers prefetch, which would swallow the tensor payload that follows a
 * SUBMITBIN header line.
 */
public final class NpuServiceClient {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 38761;
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 8000;

    private static Socket socket;
    private static InputStream in;
    private static OutputStream out;

    public record MatMulResult(float scaleC, byte[] c, long us, String error) {
        public boolean ok() { return error == null; }
    }

    private NpuServiceClient() {}

    private static synchronized void close() { closeStreams(); }

    private static synchronized void closeStreams() {
        try { if (out != null) out.flush(); } catch (Throwable ignored) {}
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null; in = null; out = null;
    }

    private static synchronized void connect() throws IOException {
        closeStreams();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        // A 512^3 submit moves ~780KB over loopback. The unbuffered streams turned
        // that into a long series of small syscalls and showed up as ~15ms on top
        // of the 5.5ms the HTP actually needed. Big socket buffers plus buffered
        // streams collapse the payload into a handful of large transfers.
        try { s.setSendBufferSize(1 << 20); } catch (Throwable ignored) {}
        try { s.setReceiveBufferSize(1 << 20); } catch (Throwable ignored) {}
        socket = s;
        in = new BufferedInputStream(s.getInputStream(), 256 * 1024);
        out = new BufferedOutputStream(s.getOutputStream(), 256 * 1024);
    }

    private static String readLineUtf8(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int ch;
        while ((ch = is.read()) >= 0) {
            if (ch == '\n') return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (ch != '\r') buf.write(ch);
        }
        if (buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void readFully(InputStream is, byte[] dst, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = is.read(dst, off, len - off);
            if (r < 0) throw new EOFException("eof after " + off + "/" + len);
            off += r;
        }
    }

    private static String field(String line, String key) {
        for (String kv : line.split(" ")) {
            if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        }
        throw new IllegalArgumentException("missing " + key + " in: " + line);
    }

    public static synchronized String request(String command) {
        if (command == null || command.isEmpty()) return "ERR EMPTY_COMMAND";
        String lastError = "unknown";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) connect();
                out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                String line = readLineUtf8(in);
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

    /**
     * Binary data plane: header line, raw A bytes, raw B bytes, then a header line
     * and the raw int8 result.
     */
    public static synchronized MatMulResult submitBinMatMul8(byte[] A, byte[] B, int m, int k, int n) {
        if (A == null || B == null || m <= 0 || k <= 0 || n <= 0) return new MatMulResult(0, null, 0, "BAD_ARGS");
        if ((long) A.length != (long) m * k || (long) B.length != (long) k * n) return new MatMulResult(0, null, 0, "BAD_SIZE");
        String lastError = "unknown";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) connect();
                out.write(("SUBMITBIN_MATMUL8 " + m + " " + k + " " + n + " " + A.length + " " + B.length + "\n").getBytes(StandardCharsets.UTF_8));
                out.write(A);
                out.write(B);
                out.flush();
                String line = readLineUtf8(in);
                if (line == null) throw new EOFException("service closed connection");
                if (!line.startsWith("OK BIN_SUBMIT")) return new MatMulResult(0, null, 0, line);
                int cbytes = Integer.parseInt(field(line, "cbytes"));
                float scaleC = Float.parseFloat(field(line, "scaleC"));
                long us = Long.parseLong(field(line, "us"));
                byte[] c = new byte[cbytes];
                readFully(in, c, cbytes);
                return new MatMulResult(scaleC, c, us, null);
            } catch (Throwable t) {
                close();
                String msg = t.getMessage();
                lastError = t.getClass().getSimpleName() + (msg == null ? "" : "(" + msg + ")");
            }
        }
        return new MatMulResult(0, null, 0, "SERVICE_UNAVAILABLE " + lastError);
    }

    public static synchronized void closeAll() { close(); }

    public static boolean isAvailable() { return request("PING").startsWith("PONG MCNPU/"); }

    public static String status() { return request("STATUS"); }

    public static String smoke() { return request("SMOKE"); }

    public static String capabilities() { return request("CAPABILITIES"); }

    /** Deterministic m x k times k x n matmul executed on the HTP service. */
    public static String matMul(int m, int k, int n) { return request("EXEC_MATMUL " + m + " " + k + " " + n); }

    /** INT8 quantized matmul: the datatype HTP accelerates natively. */
    public static String matMulInt8(int m, int k, int n) { return request("EXEC_MATMUL8 " + m + " " + k + " " + n); }

    public static String add(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length || a.length > 1024) return "ERR ADD_SIZE";
        StringBuilder sa = new StringBuilder(), sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return "ERR ADD_NON_FINITE";
            if (i > 0) { sa.append(','); sb.append(','); }
            sa.append(Float.toString(a[i]));
            sb.append(Float.toString(b[i]));
        }
        return request("EXEC_ADD " + sa + "|" + sb);
    }
}
