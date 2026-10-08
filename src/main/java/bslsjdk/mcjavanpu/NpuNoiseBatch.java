package bslsjdk.mcjavanpu;

/**
 * The batched noise request - the protocol the assist path speaks.
 *
 * WHY THIS EXISTS
 *
 * The bench (mcnpu/tools/worldgen-bench) settled two things:
 *
 *   1. matmul terrain is dead: one chunk needed ~60,907 submissions, each paying the
 *      device's ~2.5 ms fixed cost. 0.05x.
 *   2. a kernel that takes a block of points and returns a block of values reaches
 *      4.7x today and 9.6x if the per-call fixed cost is fixed - because it pays that
 *      cost once per block instead of once per group.
 *
 * So the assist path is a batched noise evaluation, and this class is its wire format.
 *
 * WHAT IT DOES NOT DO
 *
 * It is not a terrain generator and it does not decide anything about density. It moves
 * "evaluate these noise channels at these points" across to the NPU and brings numbers
 * back. Everything non-regular - spline, range choice, cache semantics, carvers - stays
 * on the CPU, which is what makes this assist rather than takeover.
 *
 * THE ONE DESIGN DECISION THAT MATTERS
 *
 * The request ships the DERIVED perlin tables (offsets + 256-byte permutation per
 * octave), not the world seed.
 *
 * The obvious alternative - ship the seed and let the DSP walk Xoroshiro128++ itself -
 * puts the single largest correctness risk of the whole project on the far side of a
 * boundary where we cannot test it incrementally. Getting the PRNG sequence subtly wrong
 * does not crash; it produces a plausible but different world, which is the failure mode
 * the terrain gate exists to prevent.
 *
 * Shipping the tables costs 280 bytes per octave. Nineteen octaves is ~5.3 KB against a
 * ~76 KB result payload - under 7% - and it removes that risk entirely. The kernel then
 * needs no PRNG at all, and any mismatch shows up as a table diff we can print.
 *
 * DEFAULT BEHAVIOUR
 *
 * available() is false until a service advertises NOISE_BATCH. Until then every call
 * goes to the CPU reference and returns exactly what NormalNoise.getValue returns. The
 * assist path is therefore a no-op today, not a slower path.
 */
public final class NpuNoiseBatch {

    // ---- wire format ------------------------------------------------------

    /** 'NB' request, 'NR' reply. */
    public static final int MAGIC_REQ = 0x4E42;
    public static final int MAGIC_RES = 0x4E52;

    public static final int VERSION = 2;

    /** bit0: reply payload is int8 (else fp32). bit1: perlin tables included. */
    public static final int FLAG_INT8 = 1;
    public static final int FLAG_TABLES = 2;

    public static final String OP = "SUBMITBIN_NOISEBATCH";

    /** Per-octave derived data. 24 bytes of offsets + 256 bytes of permutation. */
    public static final int TABLE_BYTES = 256 + 24;

    /**
     * Per-channel header, excluding octaves: firstOctave u8, octaveCount u8,
     * normalization f32, xzScale f32, yScale f32.
     */
    public static final int CHANNEL_HEADER_BYTES = 2 + 12;

    private NpuNoiseBatch() {}

    // ---- capability -------------------------------------------------------

    private static volatile int probeState = 0;      // 0 unknown, 1 yes, 2 no
    private static volatile String probeNote = "not probed";

    /**
     * Does the service implement the noise kernel?
     *
     * Probed once and cached: this sits in front of every batched evaluation, and a
     * capability check that opens a socket per call would itself become the cost we are
     * trying to remove. Absent a kernel we fall through to the CPU reference, which is
     * the same answer the program would have computed anyway.
     */
    public static boolean available() {
        if (probeState == 0) probe();
        return probeState == 1;
    }

    public static String probeNote() { return probeNote; }

    private static synchronized void probe() {
        if (probeState != 0) return;
        try {
            String caps = NpuServiceClient.capabilities();
            if (caps == null) {
                probeState = 2;
                probeNote = "no capabilities reply";
                return;
            }
            // A real service that has the kernel says so; anything else means the CPU
            // path stays in charge. Assuming support because the service is up would put
            // an untested interpretation of the world behind the gate.
            boolean has = caps.contains("NOISE_BATCH");
            probeState = has ? 1 : 2;
            probeNote = has ? "kernel advertised" : "kernel absent, CPU reference";
            NpuLog.log("noise batch probe: " + probeNote);
        } catch (Throwable t) {
            probeState = 2;
            probeNote = "probe failed: " + t.getClass().getSimpleName();
            NpuLog.log("noise batch probe: " + probeNote);
        }
    }

    /** Test hook: force the probe result without a service. */
    public static void forceAvailability(boolean v) {
        probeState = v ? 1 : 2;
        probeNote = v ? "forced on" : "forced off";
    }

    // ---- request ----------------------------------------------------------

    /**
     * One channel: which noise to evaluate, at what coordinate scale, with what
     * per-octave data. The kernel needs all of it because a channel is not identified by
     * a name - two worlds can have the same channel with different amplitudes.
     */
    public static final class Channel {
        public final int firstOctave;
        public final float[] amplitudes;      // per octave, 0 means skipped
        public final float normalization;
        public final float xzScale, yScale;
        /** Per non-zero octave: {xo, yo, zo, perm[256]}. */
        public final double[][] offsets;
        public final byte[][] perms;

        public Channel(int firstOctave, float[] amplitudes, float normalization,
                       float xzScale, float yScale, double[][] offsets, byte[][] perms) {
            this.firstOctave = firstOctave;
            this.amplitudes = amplitudes;
            this.normalization = normalization;
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.offsets = offsets;
            this.perms = perms;
        }

        int activeOctaves() {
            int n = 0;
            for (float a : amplitudes) if (a != 0f) n++;
            return n;
        }
    }

    /**
     * Builds a request for {@code chunkCount} chunks of one dimension of one world.
     *
     * Points are not listed. Chunks are described and the kernel derives the lattice
     * itself, because the lattice is perfectly regular - that regularity is the whole
     * reason a 16-chunk request is ~224 bytes instead of 235 KB.
     */
    public static byte[] encodeRequest(long seed, Channel[] channels,
                                       int cellXZ, int cellY,
                                       int lx, int ly, int lz,
                                       int[] chunkX, int[] chunkZ, int[] minY,
                                       int chunkCount) {
        int chanBytes = 0;
        for (Channel c : channels) {
            chanBytes += CHANNEL_HEADER_BYTES + c.amplitudes.length * 4
                    + c.activeOctaves() * TABLE_BYTES;
        }
        int total = 8 + 8 + 6 + chunkCount * 12 + chanBytes;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(total)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);

        b.putShort((short) MAGIC_REQ);
        b.putShort((short) VERSION);
        b.putShort((short) (FLAG_INT8 | FLAG_TABLES));
        b.putShort((short) channels.length);
        b.putShort((short) chunkCount);
        b.put((byte) cellXZ);
        b.put((byte) cellY);
        b.putShort((short) lx);
        b.putShort((short) ly);
        b.putShort((short) lz);
        b.putLong(seed);

        for (Channel c : channels) {
            b.put((byte) c.firstOctave);
            b.put((byte) c.amplitudes.length);
            b.putFloat(c.normalization);
            b.putFloat(c.xzScale);
            b.putFloat(c.yScale);
            for (float a : c.amplitudes) b.putFloat(a);
            int oi = 0;
            for (int o = 0; o < c.amplitudes.length; o++) {
                if (c.amplitudes[o] == 0f) continue;
                double[] off = c.offsets[oi];
                b.putDouble(off[0]);
                b.putDouble(off[1]);
                b.putDouble(off[2]);
                b.put(c.perms[oi]);
                oi++;
            }
        }
        for (int i = 0; i < chunkCount; i++) {
            b.putInt(chunkX[i]);
            b.putInt(chunkZ[i]);
            b.putInt(minY[i]);
        }
        return b.array();
    }

    /** Header line the service parses before reading the binary body. */
    public static String requestHeader(int chunkCount, int channelCount, int pointCount,
                                       int bodyBytes) {
        return OP + " chunks=" + chunkCount + " channels=" + channelCount
                + " points=" + pointCount + " bytes=" + bodyBytes;
    }

    // ---- result -----------------------------------------------------------

    public static final class Result {
        public final float[][] values;    // [channel][point]
        public final int channels, points;
        public final long us;
        public final String error;

        Result(float[][] v, int channels, int points, long us, String error) {
            this.values = v; this.channels = channels; this.points = points;
            this.us = us; this.error = error;
        }

        public boolean ok() { return error == null && values != null; }
    }

    /**
     * Decodes a reply body: per-channel scale, then channel-major int8 payload.
     *
     * int8 rather than fp32 because the bench measured the quantisation error at
     * MAE 0.0004 on density - below anything that changes terrain - and it is a quarter
     * of the bytes coming back.
     */
    public static Result decode(byte[] body, int channels, int points, boolean int8, long us) {
        if (body == null) return new Result(null, channels, points, us, "null body");
        int expected = channels * 4 + (int8 ? channels * points : channels * points * 4);
        if (body.length < expected) {
            return new Result(null, channels, points, us,
                    "short body " + body.length + "<" + expected);
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(body)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[][] out = new float[channels][points];
        float[] scale = new float[channels];
        for (int c = 0; c < channels; c++) scale[c] = b.getFloat();
        for (int c = 0; c < channels; c++) {
            float[] row = out[c];
            float s = scale[c];
            for (int p = 0; p < points; p++) {
                row[p] = int8 ? ((float) b.get()) * s : b.getFloat();
            }
        }
        return new Result(out, channels, points, us, null);
    }

    // ---- CPU reference ----------------------------------------------------

    /**
     * The same evaluation on the CPU, delegating to NormalNoise so the result is
     * bit-identical to what the density program computes today.
     *
     * This is what runs until a kernel exists, and it is also the oracle the kernel will
     * be diffed against.
     */
    public static float[][] evalOnCpu(NpuNoise.NormalNoise[] noises,
                                      double[] xzScale, double[] yScale,
                                      float[] px, float[] py, float[] pz, int points) {
        float[][] out = new float[noises.length][points];
        for (int c = 0; c < noises.length; c++) {
            NpuNoise.NormalNoise n = noises[c];
            float[] row = out[c];
            if (n == null) continue;
            double sx = xzScale[c], sy = yScale[c];
            for (int p = 0; p < points; p++) {
                row[p] = (float) n.getValue(px[p] * sx, py[p] * sy, pz[p] * sx);
            }
        }
        return out;
    }

    // ---- stats ------------------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicLong BATCHES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong POINTS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CHUNKS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong REJECTED =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile String lastReject = "none";

    static void recordBatch(int chunks, int points) {
        BATCHES.incrementAndGet();
        CHUNKS.addAndGet(chunks);
        POINTS.addAndGet(points);
    }

    static void recordReject(String why) {
        REJECTED.incrementAndGet();
        lastReject = why;
    }

    public static String summary() {
        long b = BATCHES.get();
        if (b == 0) {
            return "noisebatch: idle (kernel "
                    + (available() ? "present" : "absent -> CPU reference") + ")";
        }
        return "noisebatch: batches=" + b
                + " chunks=" + CHUNKS.get()
                + " points=" + POINTS.get()
                + " per_batch=" + (CHUNKS.get() / b) + "chunks/" + (POINTS.get() / b) + "pts"
                + " rejected=" + REJECTED.get()
                + " last=" + lastReject;
    }

    public static void resetStats() {
        BATCHES.set(0); POINTS.set(0); CHUNKS.set(0); REJECTED.set(0);
        lastReject = "none";
    }


    /** bit2: the body carries explicit xyz coordinates instead of chunk/lattice descriptors. */
    public static final int FLAG_POINTS = 4;

    /**
     * Builds a request that ships the coordinates.
     *
     * Protocol v2 describes chunks and lets the kernel derive the lattice, but that makes
     * the point ORDER part of the wire contract - and an order mismatch does not crash, it
     * permutes which value belongs to which cell, which is the wrong-noise failure this
     * class exists to prevent. The caller already holds explicit px/py/pz planes, so
     * shipping them costs nothing and removes the whole class.
     */
    public static byte[] encodeRequestPoints(Channel[] channels,
                                             float[] px, float[] py, float[] pz, int points) {
        int chanBytes = 0;
        for (Channel c : channels) {
            chanBytes += CHANNEL_HEADER_BYTES + c.amplitudes.length * 4
                    + c.activeOctaves() * TABLE_BYTES;
        }
        int total = 8 + 8 + 6 + chanBytes + points * 12;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(total)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) MAGIC_REQ);
        b.putShort((short) VERSION);
        b.putShort((short) (FLAG_INT8 | FLAG_TABLES | FLAG_POINTS));
        b.putShort((short) channels.length);
        b.putShort((short) 0);                 // chunkCount unused in this form
        b.put((byte) 8); b.put((byte) 16);     // cell sizes unused, kept for field parity
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putLong(0L);
        for (Channel c : channels) {
            b.put((byte) c.firstOctave);
            b.put((byte) c.amplitudes.length);
            b.putFloat(c.normalization);
            b.putFloat(c.xzScale);
            b.putFloat(c.yScale);
            for (float a : c.amplitudes) b.putFloat(a);
            int oi = 0;
            for (int o = 0; o < c.amplitudes.length; o++) {
                if (c.amplitudes[o] == 0f) continue;
                double[] off = c.offsets[oi];
                b.putDouble(off[0]);
                b.putDouble(off[1]);
                b.putDouble(off[2]);
                b.put(c.perms[oi]);
                oi++;
            }
        }
        for (int p = 0; p < points; p++) b.putFloat(px[p]);
        for (int p = 0; p < points; p++) b.putFloat(py[p]);
        for (int p = 0; p < points; p++) b.putFloat(pz[p]);
        return b.array();
    }

    /** Packs live NormalNoise instances into the wire form. */
    public static Channel[] fromNoises(NpuNoise.NormalNoise[] noises,
                                       double[] xzScale, double[] yScale) {
        Channel[] out = new Channel[noises.length];
        for (int c = 0; c < noises.length; c++) {
            NpuNoise.NormalNoise n = noises[c];
            if (n == null) { out[c] = null; continue; }
            int k = n.amplitudes.length;
            float[] amp = new float[k];
            double[][] off = new double[k][];
            byte[][] perm = new byte[k][];
            int cnt = 0;
            for (int o = 0; o < k; o++) {
                amp[o] = (float) n.amplitudes[o];
                if (n.amplitudes[o] == 0.0) continue;
                NpuNoise.PerlinNoise pn = n.levels()[o];
                if (pn == null) continue;
                off[cnt] = new double[] { pn.xo, pn.yo, pn.zo };
                byte[] pb = new byte[256];
                for (int i = 0; i < 256; i++) pb[i] = (byte) pn.p[i];
                perm[cnt] = pb;
                cnt++;
            }
            out[c] = new Channel(n.firstOctave, amp, (float) n.normalization(),
                    (float) xzScale[c], (float) yScale[c],
                    java.util.Arrays.copyOf(off, cnt), java.util.Arrays.copyOf(perm, cnt));
        }
        return out;
    }

    /**
     * Evaluates through the service. Returns null on any failure so the caller falls back
     * to the CPU reference - a failed kernel must never become wrong terrain.
     */
    public static float[][] evalViaService(NpuNoise.NormalNoise[] noises,
                                           double[] xzScale, double[] yScale,
                                           float[] px, float[] py, float[] pz, int points) {
        if (!available() || noises == null || noises.length == 0 || points <= 0) return null;
        try {
            Channel[] ch = fromNoises(noises, xzScale, yScale);
            byte[] body = encodeRequestPoints(ch, px, py, pz, points);
            NpuServiceClient.NoiseResult r =
                    NpuServiceClient.noiseBatch(body, 0, noises.length, points);
            if (r == null || r.body == null) return null;
            Result res = decode(r.body, noises.length, points, true, r.us);
            if (!res.ok()) { recordReject("decode: " + res.error); return null; }
            return res.values;
        } catch (Throwable t) {
            recordReject(t.getClass().getSimpleName());
            return null;
        }
    }

}
