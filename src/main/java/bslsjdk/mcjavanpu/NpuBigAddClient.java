package bslsjdk.mcjavanpu;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One logical element-wise add over arrays of any length, executed on the HTP
 * through the MCNPU service and merged back into a single output.
 *
 * This is the mod-side counterpart of the service's BINADD data plane. The
 * caller hands over two float arrays and gets one result back; splitting the
 * work into fixed-shape ways, running them over several sockets and reassembling
 * them by offset are all internal.
 *
 * <p><b>What this is for.</b> Two things, and it is worth keeping them apart.
 * It is the end-to-end proof that the game process can push real data to the
 * HTP and get it back intact, and it is the transport for any genuinely
 * element-wise bulk work. It is <i>not</i> a terrain accelerator on its own:
 * ADD has no accumulation chain, so it proves bandwidth and correctness, not
 * speed over the CPU. Anything that is supposed to make world generation faster
 * has to arrive as a kernel that replaces a real cost, which is a separate step.
 *
 * <p><b>Cost, measured on a Snapdragon 8s Gen 3 at way=16384:</b>
 * <ul>
 *   <li><b>Time</b> - about 0.3 microseconds per element end to end. A full
 *       9x9 block of 7,962,624 elements took 2.4 s twice, reproducibly, with
 *       zero mismatches. Budget from that number, not from a hope.</li>
 *   <li><b>Memory</b> - 12 bytes per element live at once: both inputs plus the
 *       output. A 9x9 block is therefore ~95 MB in one allocation inside the
 *       game process, which is not a realistic thing to ask for. Call this per
 *       chunk (~98k elements, ~1.2 MB), not per 9x9.</li>
 * </ul>
 *
 * <p><b>Failure is a return value, never an exception.</b> This runs under world
 * generation, where a thrown exception does not degrade - it takes the world
 * down. Every path returns a {@link Result} with {@code out == null} and a
 * status string that says which stage refused, so the caller can fall back to
 * the CPU reference. A null result with no log entry would be a bug here; the
 * failure is always written to the module log as well.
 *
 * <p><b>Call it from a worker thread.</b> A single chunk is a few milliseconds,
 * but a large batch is seconds, and this blocks for the whole duration. Calling
 * it on the render or server thread is a stall, not a slowdown.
 *
 * <p><b>Tail handling.</b> A trailing way that is shorter than the way size is
 * zero-padded: fresh float arrays are already zero and {@code x + 0 == x}, so
 * the merged output is correct without any special case at the read-back end.
 */
public final class NpuBigAddClient {

    /** Largest way size, in elements, that the service reported it can build. */
    private static volatile int cachedMaxWay;

    private NpuBigAddClient() {}

    /**
     * The largest ADD shape this device accepted, read from the service.
     *
     * Read rather than assumed: 16384 was only the largest shape we happened to
     * have exercised, not a limit the device ever stated, and the on-device probe
     * later measured 65536. A stale constant here would silently quadruple the
     * number of round trips. If the service cannot be asked, this falls back to
     * 16384 because that one is known to work.
     */
    public static int maxWay() {
        int v = cachedMaxWay;
        if (v > 0) return v;
        synchronized (NpuBigAddClient.class) {
            v = cachedMaxWay;
            if (v > 0) return v;
            int parsed = 0;
            try {
                String caps = NpuServiceClient.capabilities();
                if (caps != null) {
                    final String key = "max_elements=";
                    int at = caps.indexOf(key);
                    if (at >= 0) {
                        int end = at + key.length();
                        int stop = end;
                        while (stop < caps.length()) {
                            char c = caps.charAt(stop);
                            if (c < '0' || c > '9') break;
                            stop++;
                        }
                        if (stop > end) {
                            try { parsed = Integer.parseInt(caps.substring(end, stop)); }
                            catch (NumberFormatException ignored) { parsed = 0; }
                        }
                    }
                }
            } catch (Throwable t) {
                NpuLog.warn("bigadd: capabilities unavailable, keeping 16384: " + t);
            }
            v = parsed > 0 ? parsed : 16384;
            cachedMaxWay = v;
            NpuLog.log("bigadd: max_way=" + v + (parsed > 0 ? " (from service)" : " (fallback)"));
            return v;
        }
    }

    /** Forget the cached way size; the next call asks the service again. */
    public static void resetMaxWay() { cachedMaxWay = 0; }

    /** Body size one IPC call aims for, so no single allocation on the service is huge. */
    private static final long TARGET_BODY_BYTES = 2L * 1024 * 1024;

    public static final class Result {
        /** Merged output, or null when any part of the run failed. */
        public final float[] out;
        public final int ways;
        public final int wayElements;
        public final int okWays;
        public final int calls;
        public final long totalUs;
        public final String status;

        Result(float[] out, int ways, int wayElements, int okWays, int calls, long totalUs, String status) {
            this.out = out; this.ways = ways; this.wayElements = wayElements;
            this.okWays = okWays; this.calls = calls; this.totalUs = totalUs;
            this.status = status;
        }

        public boolean ok() { return out != null && status.startsWith("OK"); }

        @Override public String toString() { return status; }
    }

    /**
     * Adds {@code a} and {@code b} on the HTP using the largest shape the device
     * accepted and two sockets.
     *
     * @return a result whose {@code out} is the merged sum, or null-valued when
     *         the run failed; {@link #add(float[], float[], int, int)} explains
     *         the parameters.
     */
    public static Result add(float[] a, float[] b) {
        return add(a, b, 0, 2);
    }

    /**
     * Adds {@code a} and {@code b} on the HTP.
     *
     * @param a left operand; must be the same length as {@code b}
     * @param b right operand
     * @param wayElements elements per way. 0 or negative means "as large as the
     *                    device allows" ({@link #maxWay()}); larger values are
     *                    clamped to it. One shape is used for every way on
     *                    purpose: the service builds its graph once and reuses
     *                    it, so a run of 486 ways pays for one graph build.
     * @param parallelism socket count. Sockets overlap encode, transfer and
     *                    decode with execute; the service serialises execute
     *                    behind one lock, so this does not multiply the device.
     *                    Two is the measured good default - four plus the
     *                    availability poller reached the service's accept
     *                    ceiling, and each refused socket was closed without a
     *                    reply, so the caller sat in a 15 s read timeout.
     */
    public static Result add(float[] a, float[] b, int wayElements, int parallelism) {
        long t0 = System.nanoTime();
        if (a == null || b == null || a.length != b.length) {
            return fail(t0, 0, 0, 0, "ERR BIGADD_ARGS lengths="
                    + (a == null ? -1 : a.length) + "/" + (b == null ? -1 : b.length));
        }
        if (a.length == 0) return new Result(new float[0], 0, 0, 0, 0, 0, "OK BIGADD empty");

        final int cap = maxWay();
        final int n = wayElements <= 0 ? cap : Math.min(wayElements, cap);
        final int p = Math.max(1, Math.min(parallelism <= 0 ? 2 : parallelism, 16));
        final int total = a.length;
        final int ways = (total + n - 1) / n;
        final float[] out = new float[total];

        // One way before all of them.
        //
        // A 486-way run that fails for one reason reports 486 identical failures
        // and needs minutes to say so, and each one arrives as a bare timeout
        // with the reason lost. One way succeeds or fails in a single round trip
        // and returns the service's own reply, which is the only thing that
        // separates an unsupported shape from a stalled service - the difference
        // between "change the way size" and "wait for it".
        {
            float[] pa = new float[n], pb = new float[n];
            int plen = Math.min(n, total);
            System.arraycopy(a, 0, pa, 0, plen);
            System.arraycopy(b, 0, pb, 0, plen);
            BinResult pr = binAdd(new float[][]{pa}, new float[][]{pb}, n);
            if (!(pr.status.startsWith("OK") && pr.okCount == 1)) {
                return fail(t0, ways, n, 1,
                        "ERR BIGADD_PREFLIGHT ways=" + ways + " n=" + n + " reply=" + pr.status);
            }
        }

        final AtomicInteger okWays = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger unwritten = new AtomicInteger();
        final StringBuilder firstError = new StringBuilder();

        int groups = Math.min(p, ways);
        CountDownLatch latch = new CountDownLatch(groups);
        int base = ways / groups;
        int extra = ways % groups;
        int cursor = 0;
        for (int g = 0; g < groups; g++) {
            int groupWays = base + (g < extra ? 1 : 0);
            final int start = cursor;
            cursor += groupWays;
            final int gw = groupWays;
            Thread t = new Thread(() -> {
                try {
                    int sub = maxCasesPerCall(n);
                    int w = 0;
                    while (w < gw) {
                        int count = Math.min(sub, gw - w);
                        float[][] aa = new float[count][n];
                        float[][] bb = new float[count][n];
                        for (int j = 0; j < count; j++) {
                            int off = (start + w + j) * n;
                            int len = Math.min(n, total - off);
                            // Fresh arrays are already zero, so a short tail is
                            // correct padding with no special case.
                            if (len > 0) {
                                System.arraycopy(a, off, aa[j], 0, len);
                                System.arraycopy(b, off, bb[j], 0, len);
                            }
                        }
                        calls.incrementAndGet();
                        BinResult r = binAdd(aa, bb, n);
                        // ok must equal count, not merely "the header says OK". A
                        // partly served batch still comes back with a full-length
                        // body, and copying it would write the unwritten ways
                        // into the merged result as zeros - which is exactly how
                        // a 486-way run once reported 99.5% wrong with no error
                        // anywhere in the log.
                        boolean served = r.status.startsWith("OK") && r.okCount == count;
                        if (served) {
                            okWays.addAndGet(count);
                            for (int j = 0; j < count; j++) {
                                int off = (start + w + j) * n;
                                int len = Math.min(n, total - off);
                                if (len <= 0) continue;
                                // The service fills output it never wrote with
                                // -999, so counting it separates "the HTP
                                // returned zeros" from "the HTP never ran".
                                for (int i = 0; i < len; i++) {
                                    if (r.out[j][i] == -999f) unwritten.incrementAndGet();
                                }
                                System.arraycopy(r.out[j], 0, out, off, len);
                            }
                        } else {
                            failures.incrementAndGet();
                            synchronized (firstError) {
                                if (firstError.length() == 0) firstError.append(r.status);
                            }
                        }
                        w += count;
                    }
                } catch (Throwable th) {
                    failures.incrementAndGet();
                    synchronized (firstError) {
                        if (firstError.length() == 0) firstError.append(String.valueOf(th.getMessage()));
                    }
                } finally {
                    latch.countDown();
                }
            }, "mcjavanpu-bigadd-" + g);
            t.setDaemon(true);
            t.start();
        }
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        long us = (System.nanoTime() - t0) / 1000L;
        int ok = okWays.get();
        int bad = failures.get();
        int unw = unwritten.get();
        if (bad == 0 && unw == 0 && ok == ways) {
            String status = "OK BIGADD total=" + total + " ways=" + ways + " n=" + n
                    + " sockets=" + groups + " calls=" + calls.get() + " ok=" + ok + "/" + ways
                    + " us=" + us;
            NpuLog.log("bigadd: " + status);
            return new Result(out, ways, n, ok, calls.get(), us, status);
        }
        return fail(t0, ways, n, calls.get(),
                "ERR BIGADD failed_batches=" + bad + " unwritten=" + unw
                        + " ok=" + ok + "/" + ways + " first=" + firstError);
    }

    private static Result fail(long t0, int ways, int n, int calls, String status) {
        long us = (System.nanoTime() - t0) / 1000L;
        NpuLog.warn("bigadd: " + status);
        return new Result(null, ways, n, 0, calls, us, status);
    }

    /** Largest case count one call may carry at this way size, body-size bound. */
    static int maxCasesPerCall(int wayElements) {
        long perCase = 8L * wayElements;   // two float32 arrays
        if (perCase <= 0) return 1;
        long c = TARGET_BODY_BYTES / perCase;
        return (int) Math.max(1, Math.min(64, c));
    }

    // ---------------------------------------------------------------- transport

    private static final class BinResult {
        String status = "ERR NO_REPLY";
        float[][] out;
        int okCount;
    }

    private static BinResult binAdd(float[][] a, float[][] b, int n) {
        BinResult r = binAddOnce(a, b, n);
        // A socket the service could not queue is closed without a reply, and a
        // closed socket reads as a full read timeout - two 9x9 segments paid that
        // and looked like a stalled device. Retrying a transport failure is cheap
        // and is what a queue overflow asks for; retrying a protocol error only
        // repeats it, so only the former is retried.
        for (int attempt = 1; attempt < 3 && isTransportFailure(r.status); attempt++) {
            try { Thread.sleep(200L * attempt); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            r = binAddOnce(a, b, n);
        }
        return r;
    }

    /** Reachable-but-refused, or accepted-then-dropped. Both mean "try again". */
    private static boolean isTransportFailure(String status) {
        return status.startsWith("ERR SERVICE_UNAVAILABLE")
                || status.startsWith("ERR EMPTY_REPLY")
                || status.startsWith("ERR OVERLOAD");
    }

    /**
     * One BINADD round trip on its own socket.
     *
     * A dedicated socket rather than the shared long-lived one: that connection
     * is guarded by a class-wide lock, so routing 486 ways through it would make
     * the whole run strictly serial and would also block every status and health
     * RPC for the duration.
     */
    private static BinResult binAddOnce(float[][] a, float[][] b, int n) {
        BinResult res = new BinResult();
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            res.status = "ERR BINADD_ARGS";
            return res;
        }
        int cases = a.length;
        ByteBuffer body = ByteBuffer.allocate(4 * 2 * n * cases).order(ByteOrder.LITTLE_ENDIAN);
        for (int c = 0; c < cases; c++) {
            for (int i = 0; i < n; i++) body.putFloat(a[c][i]);
            for (int i = 0; i < n; i++) body.putFloat(b[c][i]);
        }
        byte[] payload = body.array();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", NpuServiceClient.port()), 1500);
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            OutputStream os = new BufferedOutputStream(socket.getOutputStream(), 256 * 1024);
            InputStream is = new BufferedInputStream(socket.getInputStream(), 256 * 1024);
            os.write(("BINADD " + n + " " + cases + "\n").getBytes(StandardCharsets.UTF_8));
            int off = 0;
            while (off < payload.length) {
                int chunk = Math.min(64 * 1024, payload.length - off);
                os.write(payload, off, chunk);
                off += chunk;
            }
            os.flush();

            String header = readReplyLine(is);
            res.status = header == null ? "ERR EMPTY_REPLY" : header;
            if (header == null) return res;

            int at = header.indexOf("ok=");
            if (at >= 0) {
                int slash = header.indexOf('/', at);
                if (slash > at) {
                    try { res.okCount = Integer.parseInt(header.substring(at + 3, slash).trim()); }
                    catch (NumberFormatException ignored) { res.okCount = 0; }
                }
            }
            if (header.startsWith("OK") && res.okCount == cases) {
                byte[] results = new byte[4 * n * cases];
                readFully(is, results, results.length);
                ByteBuffer rb = ByteBuffer.wrap(results).order(ByteOrder.LITTLE_ENDIAN);
                res.out = new float[cases][n];
                for (int c = 0; c < cases; c++) for (int i = 0; i < n; i++) res.out[c][i] = rb.getFloat();
            } else if (header.startsWith("OK")) {
                // Drain anyway so the stream stays in step for the next call on
                // this socket, then report the shortfall instead of handing back
                // results that were never produced.
                readFully(is, new byte[4 * n * cases], 4 * n * cases);
                res.status = "ERR BINADD_INCOMPLETE ok=" + res.okCount + "/" + cases + " " + header;
            }
            return res;
        } catch (Throwable t) {
            res.status = "ERR SERVICE_UNAVAILABLE " + t.getClass().getSimpleName()
                    + " msg=" + String.valueOf(t.getMessage());
            return res;
        }
    }

    private static void readFully(InputStream is, byte[] dst, int len) throws java.io.IOException {
        int off = 0;
        while (off < len) {
            int r = is.read(dst, off, len - off);
            if (r < 0) throw new EOFException("eof after " + off + "/" + len);
            off += r;
        }
    }

    private static String readReplyLine(InputStream is) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(128);
        int ch;
        while ((ch = is.read()) >= 0) {
            if (ch == '\n') return sb.toString();
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 65536) throw new java.io.IOException("reply line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // --------------------------------------------------------------- self test

    /**
     * Runs one split add against synthetic data and reports the worst divergence
     * from a CPU reference, so a merge that is off by one way shows up as a
     * magnitude rather than as a pass flag.
     */
    public static String selfTest(int total, int wayElements, int parallelism) {
        float[] a = new float[total], b = new float[total];
        for (int i = 0; i < total; i++) {
            a[i] = ((i % 97) - 48) / 32.0f;
            b[i] = ((i % 53) + 1) / 64.0f;
        }
        long c0 = System.nanoTime();
        float[] want = cpuAdd(a, b);
        long cpuUs = (System.nanoTime() - c0) / 1000L;
        Result r = add(a, b, wayElements, parallelism);
        if (!r.ok()) return "BIGADD SELFTEST total=" + total + " " + r.status;
        double maxAbs = 0;
        int bad = 0;
        for (int i = 0; i < total; i++) {
            double d = Math.abs(r.out[i] - want[i]);
            // Written as "not within tolerance" rather than "greater than
            // tolerance": a NaN makes the latter false, so a failed element
            // would vanish from both the count and the maximum.
            if (!(d <= 1e-3)) bad++;
            if (d > maxAbs) maxAbs = d;
        }
        return "BIGADD SELFTEST total=" + total + " ways=" + r.ways + " n=" + r.wayElements
                + " " + r.status + " cpu_us=" + cpuUs + " max_abs=" + maxAbs + " mismatch=" + bad;
    }

    /** CPU reference the self test compares against. */
    public static float[] cpuAdd(float[] a, float[] b) {
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] + b[i];
        return out;
    }
}
