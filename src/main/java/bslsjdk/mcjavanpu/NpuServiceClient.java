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

    /**
     * Hard cap on one tensor. The service is single threaded, so an oversized submit blocks
     * every later request behind it until it finishes - seen on device as a burst of
     * SocketTimeoutException that only clears after the game is closed. 16 MiB is far above any
     * legitimate batch here (a 512-block light batch is ~262KB) and far below the sizes that
     * wedge the service.
     */
    private static final long MAX_PAYLOAD_BYTES = 16L * 1024 * 1024;

    /** After a failure, back off instead of queueing more work on a busy service. */
    private static final long COOLDOWN_MS = 5000;
    private static volatile long cooldownUntil;
    private static volatile String lastFailure = "";

    public static String lastFailure() { return lastFailure; }

    private static boolean cooling() {
        long until = cooldownUntil;
        return until != 0 && System.currentTimeMillis() < until;
    }

    private static void enterCooldown(String why) {
        lastFailure = why;
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
    }

    private static void clearCooldown() {
        cooldownUntil = 0;
        lastFailure = "";
    }

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
        try { s.setKeepAlive(true); } catch (Throwable ignored) {}
        // A 512^3 submit moves ~780KB over loopback. The unbuffered streams turned
        // that into a long series of small syscalls and showed up as ~15ms on top
        // of the 5.5ms the HTP actually needed. Big socket buffers plus buffered
        // streams collapse the payload into a handful of large transfers.
        try { s.setSendBufferSize(1 << 20); } catch (Throwable ignored) {}
        try { s.setReceiveBufferSize(1 << 20); } catch (Throwable ignored) {}
        socket = s;
        in = new BufferedInputStream(s.getInputStream(), 256 * 1024);
        out = new BufferedOutputStream(s.getOutputStream(), 256 * 1024);
        // Identify the long-lived game/client session once. After this point all RPCs,
        // including health/status traffic, reuse this exact socket.
        out.write("HELLO MCJAVA_NPU/1\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        String hello = readLineUtf8(in);
        if (hello == null || !hello.startsWith("OK HELLO")) {
            closeStreams();
            throw new IOException("bad HELLO reply: " + hello);
        }
    }

    private static String readLineUtf8(InputStream is) throws IOException {
        // Recycled buffer. The previous version allocated a stream and copied to a byte[] per line,
        // on every submission; the data path should not allocate at all for a header.
        byte[] b = LINE_BUF.get();
        int n = 0;
        int ch;
        while ((ch = is.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r' && n < b.length) b[n++] = (byte) ch;
        }
        if (n == 0 && ch < 0) return null;
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    /** Reused header buffer; one per thread, never shared. */
    private static final ThreadLocal<byte[]> LINE_BUF =
            ThreadLocal.withInitial(() -> new byte[1024]);

    private static String unusedReadLineUtf8(InputStream is) throws IOException {
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
        if (cooling()) return "ERR SERVICE_COOLDOWN " + lastFailure;
        String lastError = "unknown";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) connect();
                out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                long tAfterSend = System.nanoTime();
                String line = readLineUtf8(in);
                if (line == null) throw new EOFException("service closed connection");
                clearCooldown();
                healthy = true;
                return line;
            } catch (Throwable t) {
                close();
                healthy = false;
                String m = t.getMessage();
                lastError = t.getClass().getSimpleName() + (m == null ? "" : "(" + m + ")");
                if (t instanceof java.net.SocketTimeoutException) {
                    enterCooldown(lastError);
                    return "ERR SERVICE_BUSY " + lastError;
                }
            }
        }
        enterCooldown(lastError);
        return "ERR SERVICE_UNAVAILABLE " + lastError;
    }

    /**
     * Binary data plane: header line, raw A bytes, raw B bytes, then a header line
     * and the raw int8 result.
     */
    private static final java.util.concurrent.atomic.AtomicLong IN_LOCK_US =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong IN_LOCK_MAX_US =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong IN_LOCK_N =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Wall time measured inside the submission lock.
     *
     * This class is static synchronized, and the native side adds a second global mutex behind
     * that. The time a caller sees is therefore service time PLUS however long this thread waited
     * for the lock - and the prefetch thread and the game thread do contend for it. Recording the
     * in-lock duration separately is the only way to tell "the service is slow" apart from "we
     * queued behind someone else", and those two need opposite fixes.
     */
    private static final java.util.concurrent.atomic.AtomicLong SEND_US =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong WAIT_US =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong RECV_US =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SERVICE_US =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CALLS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_US = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong PREPARE_US = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ASSEMBLE_US = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long LAST_SERVICE_US;

    /**
     * Where a submission actually goes.
     *
     * send  - building and pushing the request bytes
     * wait  - flush to first response byte. If this dominates, the service side is the target
     * recv  - draining the result tensor
     * svc   - the service's own QNN timing, for comparison against wait
     *
     * A large gap between wait and svc means the time is not being spent computing.
     */
    public static String ioSummary() {
        long n = Math.max(1, CALLS.get());
        return "ipc_calls=" + CALLS.get()
                + " queue_wait_avg_us=" + (LOCK_WAIT_US.get() / n)
                + " ipc_send_avg_us=" + (SEND_US.get() / n)
                + " service_wait_avg_us=" + (WAIT_US.get() / n)
                + " ipc_recv_avg_us=" + (RECV_US.get() / n)
                + " npu_service_avg_us=" + (SERVICE_US.get() / n)
                + " prepare_avg_us=" + (PREPARE_US.get() / n)
                + " assemble_avg_us=" + (ASSEMBLE_US.get() / n)
                + " total_avg_us=" + (TOTAL_US.get() / n)
                + " npu_service_last_us=" + LAST_SERVICE_US;
    }

    public static String lockSummary() {
        long n = IN_LOCK_N.get();
        long avg = n == 0 ? 0 : IN_LOCK_US.get() / n;
        return "in_lock avg_us=" + avg + " max_us=" + IN_LOCK_MAX_US.get() + " n=" + n;
    }

    /**
     * Serialises access to the socket, and measures how long that serialisation costs.
     *
     * This was a plain static synchronized block. It has to serialise - one socket, one outstanding
     * request - but the cost of that must be visible, because it is not free: the difference between
     * in_lock and send+wait+recv measured 8.5 ms in the field, which is time spent waiting, not
     * computing. A fair lock is used so a prefetch thread cannot starve the game thread.
     */
    private static final java.util.concurrent.locks.ReentrantLock SUBMIT_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);
    private static final java.util.concurrent.atomic.AtomicLong LOCK_WAIT_US =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong LOCK_WAIT_MAX_US =
            new java.util.concurrent.atomic.AtomicLong();

    public static void recordPrepareUs(long us) { PREPARE_US.addAndGet(Math.max(0L, us)); }
    public static void recordAssembleUs(long us) { ASSEMBLE_US.addAndGet(Math.max(0L, us)); }

    public static String contentionSummary() {
        long n = Math.max(1, IN_LOCK_N.get());
        return "lock_wait_avg_us=" + (LOCK_WAIT_US.get() / n)
                + " lock_wait_max_us=" + LOCK_WAIT_MAX_US.get();
    }

    public static MatMulResult submitBinMatMul8(byte[] A, byte[] B, int m, int k, int n) {
        final long total0 = System.nanoTime();
        long tWait0 = total0;
        SUBMIT_LOCK.lock();
        long waitUs = (System.nanoTime() - tWait0) / 1000;
        LOCK_WAIT_US.addAndGet(waitUs);
        if (waitUs > LOCK_WAIT_MAX_US.get()) LOCK_WAIT_MAX_US.set(waitUs);
        long t = System.nanoTime();
        try {
            MatMulResult r = submitBinMatMul8Locked(A, B, m, k, n, total0);
        // Counted here, on the transport, not in the scheduler: this is the only place that
        // can distinguish "a chunk was queued" from "a request actually went out".
        NpuDiagnostics.count("transport.submits");
        NpuDiagnostics.time("transport", (System.nanoTime() - total0) * 1000L < 0
                ? 0 : System.nanoTime() - total0);
        if (r == null || r.error() != null) {
            NpuDiagnostics.fail("transport");
            String e = r == null ? "NULL_RESULT" : r.error();
            NpuDiagnostics.fail("reason." + (e.indexOf(' ') > 0 ? e.substring(0, e.indexOf(' ')) : e));
        } else {
            NpuDiagnostics.count("transport.ok");
        }
            // Counted here, at the transport, not in the scheduler. A counter in the
            // scheduler would only prove that chunks were put in a list; this proves
            // a request actually left for the NPU.
            NpuBatchMetrics.recordActualSubmit(m + "x" + k + "x" + n, r == null ? 0 : r.us());
            return r;
        } finally {
            long us = (System.nanoTime() - t) / 1000;
            IN_LOCK_US.addAndGet(us);
            IN_LOCK_N.incrementAndGet();
            if (us > IN_LOCK_MAX_US.get()) IN_LOCK_MAX_US.set(us);
            SUBMIT_LOCK.unlock();
        }
    }

    /**
     * total0 is the timestamp taken before the lock was acquired, so the end-to-end
     * figure below includes queueing. It has to be passed in: it lives in the caller's
     * frame, and reading it from here would not compile.
     */
    private static MatMulResult submitBinMatMul8Locked(byte[] A, byte[] B, int m, int k, int n, long total0) {
        if (A == null || B == null || m <= 0 || k <= 0 || n <= 0) return new MatMulResult(0, null, 0, "BAD_ARGS");
        if ((long) A.length != (long) m * k || (long) B.length != (long) k * n) return new MatMulResult(0, null, 0, "BAD_SIZE");
        if ((long) A.length + B.length > MAX_PAYLOAD_BYTES) {
            return new MatMulResult(0, null, 0, "TOO_LARGE " + ((long) A.length + B.length) + ">" + MAX_PAYLOAD_BYTES);
        }
        if (cooling()) return new MatMulResult(0, null, 0, "SERVICE_COOLDOWN " + lastFailure);
        String lastError = "unknown";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) connect();
                out.write(("SUBMITBIN_MATMUL8 " + m + " " + k + " " + n + " " + A.length + " " + B.length + "\n").getBytes(StandardCharsets.UTF_8));
                out.write(A);
                out.write(B);
                long tFlush0 = System.nanoTime();
                out.flush();
                long tAfterSend = System.nanoTime();
                String line = readLineUtf8(in);
                if (line == null) throw new EOFException("service closed connection");
                if (!line.startsWith("OK BIN_SUBMIT")) return new MatMulResult(0, null, 0, line);
                long tAfterHeader = System.nanoTime();
                int cbytes = Integer.parseInt(field(line, "cbytes"));
                float scaleC = Float.parseFloat(field(line, "scaleC"));
                long us = Long.parseLong(field(line, "us"));
                byte[] c = new byte[cbytes];
                readFully(in, c, cbytes);
                long tEnd = System.nanoTime();
                TOTAL_US.addAndGet((tEnd - total0) / 1000);
                // Split the round trip: send / service / receive. Without this the only number we
                // had was the total, and "the service is slow" and "we waste time shuffling bytes"
                // need opposite fixes. svc is the service's own QNN timing, for comparison.
                SEND_US.addAndGet((tAfterSend - tFlush0) / 1000);
                WAIT_US.addAndGet((tAfterHeader - tAfterSend) / 1000);
                RECV_US.addAndGet((tEnd - tAfterHeader) / 1000);
                SERVICE_US.addAndGet(us);
                LAST_SERVICE_US = us;
                CALLS.incrementAndGet();
                clearCooldown();
                healthy = true;
                return new MatMulResult(scaleC, c, us, null);
            } catch (Throwable t) {
                close();
                String msg = t.getMessage();
                healthy = false;
                lastError = t.getClass().getSimpleName() + (msg == null ? "" : "(" + msg + ")");
                if (t instanceof java.net.SocketTimeoutException) {
                    enterCooldown(lastError);
                    return new MatMulResult(0, null, 0, "SERVICE_BUSY " + lastError);
                }
            }
        }
        enterCooldown(lastError);
        return new MatMulResult(0, null, 0, "SERVICE_UNAVAILABLE " + lastError);
    }

    public static synchronized void closeAll() { close(); }

    /**
     * Cached health, updated only when a real request succeeds or fails.
     *
     * Game threads must never call isAvailable(): that opens a socket, sends PING and waits for
     * a reply, which on the chunk-generation path meant a network round trip per chunk - the
     * single biggest reason assist mode was slower than vanilla. Callers on the hot path read
     * this flag instead; it is refreshed by whoever actually talks to the service.
     */
    private static volatile boolean healthy;

    public static boolean healthy() { return healthy && !cooling(); }

    /**
     * Availability probe reuses the SAME persistent IPC socket as real requests.
     *
     * Never open a throw-away Socket here. The service is intentionally persistent and
     * the probe used to create a new TCP connection on every TTL expiry, which produced
     * the repeating IPC ACCEPT lines seen in the device log. A probe is just a normal
     * PING on the existing connection.
     */
    private static final long PROBE_TTL_MS = 1500L;
    private static volatile long probeAtMs = 0L;
    private static volatile boolean probeValue = false;

    public static boolean isAvailable() {
        // Health is a cached state, not a network operation. Re-probing here used to
        // turn innocent capability checks into periodic IPC traffic. Real requests
        // refresh this flag; callers that truly need a fresh probe can use
        // isAvailableNow().
        return healthy();
    }

    /** Fresh availability check, still using the persistent connection. */
    public static boolean isAvailableNow() {
        if (cooling()) return false;
        String reply = request("PING");
        boolean ok = reply != null && reply.startsWith("PONG MCNPU/");
        probeValue = ok;
        probeAtMs = System.currentTimeMillis();
        return ok;
    }

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
