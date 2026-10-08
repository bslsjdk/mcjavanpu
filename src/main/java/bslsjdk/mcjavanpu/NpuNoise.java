package bslsjdk.mcjavanpu;

/**
 * Faithful reimplementation of the noise primitives Minecraft generates terrain with.
 *
 * Level 1 of the three-stage plan: get the maths identical before touching performance.
 * Nothing here talks to the NPU yet - it is the reference that the NPU path has to match.
 *
 *   Rng        - Xoroshiro128++ as RandomSource/XoroshiroRandomSource uses it
 *   PerlinNoise - gradient lattice + trilinear interpolation
 *   NormalNoise - a stack of octaves, each a PerlinNoise at its own frequency
 *
 * The point of reimplementing rather than calling Minecraft's classes is that this code can
 * be fed to the NPU in bulk, whereas the vanilla objects can only be sampled one point at a
 * time. The parameters (base octave, amplitudes, scales) are read straight out of the vanilla
 * JSON so the shape of the terrain is defined by vanilla, not by us.
 */
public final class NpuNoise {

    private NpuNoise() {}

    // ------------------------------------------------------------------ Rng

    /** Xoroshiro128++ exactly as Minecraft's XoroshiroRandomSource drives it. */
    public static final class Rng {
        private long lo, hi;

        public Rng(long seed) { setSeed(seed); }

        public void setSeed(long seed) {
            if (seed == 0L) seed = -1L;
            this.lo = seed ^ 0x6A09E667F3BCC909L;
            this.hi = seed + 0x9E3779B97F4A7C15L;
        }

        public long nextLong() {
            long l = lo, h = hi;
            long result = Long.rotateLeft(l + h, 17) + l;
            h ^= l;
            lo = Long.rotateLeft(l, 49) ^ h ^ (h << 21);
            hi = Long.rotateLeft(h, 28);
            return result;
        }

        /** RandomSource.nextInt(n): discard the sign bit, then modulo. */
        public int nextInt(int n) {
            if (n <= 0) throw new IllegalArgumentException();
            long r = nextLong() >>> 1;
            return (int) (r % n);
        }

        public int nextInt() { return (int) nextLong(); }

        public double nextDouble() {
            return (nextLong() >>> 11) * 0x1.0p-53;
        }

        public float nextFloat() {
            return (nextLong() >>> 40) * 0x1.0p-24f;
        }
    }

    // ------------------------------------------------------- PerlinNoise

    /**
     * Perlin noise: a random gradient lattice plus smooth (quintic) interpolation.
     *
     * The lattice offsets are what make two seeds differ; they are drawn from the Rng in the
     * same order Minecraft draws them.
     */
    public static final class PerlinNoise {
        // Public because the NOISE_BATCH wire format ships exactly these: 256 permutation
        // bytes plus the three offsets per octave. Exposing the fields is cheaper and less
        // error-prone than a parallel accessor that could drift from them.
        public final int[] p = new int[512];
        public final double xo, yo, zo;

        public PerlinNoise(Rng rng) {
            // GradientNoise ctor order, read off the bytecode: three offsets first, then the
            // Fisher-Yates shuffle, and the offsets are scaled by 256.
            this.xo = rng.nextDouble() * 256.0;
            this.yo = rng.nextDouble() * 256.0;
            this.zo = rng.nextDouble() * 256.0;
            byte[] perm = new byte[256];
            for (int i = 0; i < 256; i++) perm[i] = (byte) i;
            for (int i = 0; i < 256; i++) {
                int j = rng.nextInt(256 - i);
                byte t = perm[i];
                perm[i] = perm[j + i];
                perm[j + i] = t;
            }
            for (int i = 0; i < 256; i++) p[i] = perm[i] & 255;
            for (int i = 0; i < 256; i++) p[i + 256] = perm[i] & 255;
        }

        private static double gradDot(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        private static double lerp(double t, double a, double b) { return a + t * (b - a); }

        private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

        public double getValue(double x, double y, double z) {
            double fx = x + xo, fy = y + yo, fz = z + zo;
            int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
            double dx = fx - ix, dy = fy - iy, dz = fz - iz;
            int X = ix & 255, Y = iy & 255, Z = iz & 255;
            double u = fade(dx), v = fade(dy), w = fade(dz);

            int a0 = p[X] + Y, a1 = p[X + 1] + Y;
            int b0 = p[a0] + Z, b1 = p[a0 + 1] + Z, b2 = p[a1] + Z, b3 = p[a1 + 1] + Z;

            double g000 = gradDot(p[b0], dx, dy, dz);
            double g001 = gradDot(p[b1], dx, dy, dz - 1);
            double g010 = gradDot(p[b0 + 1], dx, dy - 1, dz);
            double g011 = gradDot(p[b1 + 1], dx, dy - 1, dz - 1);
            double g100 = gradDot(p[b2], dx - 1, dy, dz);
            double g101 = gradDot(p[b3], dx - 1, dy, dz - 1);
            double g110 = gradDot(p[b2 + 1], dx - 1, dy - 1, dz);
            double g111 = gradDot(p[b3 + 1], dx - 1, dy - 1, dz - 1);

            double x00 = lerp(u, g000, g100);
            double x10 = lerp(u, g010, g110);
            double x01 = lerp(u, g001, g101);
            double x11 = lerp(u, g011, g111);
            double y0 = lerp(v, x00, x10);
            double y1 = lerp(v, x01, x11);
            return lerp(w, y0, y1);
        }
    }

    // ------------------------------------------------------- NormalNoise

    /**
     * A stack of octaves: each octave is an independent PerlinNoise sampled at its own
     * frequency, and the results are summed with per-octave amplitudes.
     *
     * This is the shape the NPU will eventually take over - one point needs 8 lattice values
     * per octave, so a channel with 9 octaves is a 72-wide dot product, not an 8-wide one.
     */
    public static final class NormalNoise {
        public final int firstOctave;
        public final double[] amplitudes;
        private final PerlinNoise[] levels;

        /** The octaves, in wire order. Null where the amplitude is zero. */
        public PerlinNoise[] levels() { return levels; }

        /** Channel normalisation - the divisor the reference applies after summing. */
        public double normalization() { return normalization; }

        public NormalNoise(long seed, int baseOctave, double baseAmplitude, double[] modifiers) {
            this.firstOctave = baseOctave;
            int n = modifiers.length;
            // NormalNoise.buildOctaves: amplitudes are baseAmplitude * parityNorm * modifier.
            double parity = Math.pow(2.0, n - 1) / (Math.pow(2.0, n) - 1.0);
            this.amplitudes = new double[n];
            this.levels = new PerlinNoise[n];
            double total = 0.0;
            Rng rng = new Rng(seed);
            for (int o = 0; o < n; o++) {
                double mod = modifiers[o];
                if (mod == 0.0) { amplitudes[o] = 0.0; levels[o] = null; continue; }
                amplitudes[o] = baseAmplitude * parity * mod;
                // vanilla builds the octave table from the top octave downwards, one shared Rng
                levels[o] = new PerlinNoise(rng);
                total += amplitudes[o];
            }
            this.normalization = total == 0.0 ? 1.0 : total;
        }

        private final double normalization;

        /** Number of octaves = the k-width of the dot product for one point. */
        public int octaves() { return amplitudes.length; }

        public double getValue(double x, double y, double z) {
            // Measure, do not guess. Every plan for putting terrain on the NPU has to
            // answer one question first: how much of a density evaluation is actually
            // noise? Noise is the only part large and regular enough to be worth moving,
            // and it is also the part that is NOT a matmul - so if the share turns out to
            // be small the idea dies here, before anyone writes a DSP kernel.
            //
            // Sampled 1-in-64 and accumulated per thread: a shared counter incremented on
            // every call would ping-pong one cache line across the worker threads and
            // distort the very thing being measured.
            long[] c = NOISE_TL.get();
            long n = ++c[0];
            if ((n & SAMPLE_MASK) != 0) return compute(x, y, z);
            long t0 = System.nanoTime();
            double v = compute(x, y, z);
            c[2] += System.nanoTime() - t0;
            c[1]++;
            if (n >= FLUSH_AT) flush(c);
            return v;
        }

        private double compute(double x, double y, double z) {
            double v = 0.0;
            for (int o = 0; o < amplitudes.length; o++) {
                if (levels[o] == null || amplitudes[o] == 0.0) continue;
                double freq = Math.pow(2.0, firstOctave + o);
                v += levels[o].getValue(x * freq, y * freq, z * freq) * amplitudes[o];
            }
            return v / normalization;
        }
    }

    // ------------------------------------------------- noise cost measurement

    private static final int SAMPLE_MASK = 63;          // time 1 in 64 calls
    private static final long FLUSH_AT = 4096L;
    private static final java.util.concurrent.atomic.AtomicLong NOISE_CALLS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong NOISE_SAMPLED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong NOISE_NS =
            new java.util.concurrent.atomic.AtomicLong();

    /** [0] calls since flush, [1] sampled count, [2] sampled nanoseconds. */
    private static final ThreadLocal<long[]> NOISE_TL =
            ThreadLocal.withInitial(() -> new long[3]);

    private static void flush(long[] c) {
        NOISE_CALLS.addAndGet(c[0]);
        NOISE_SAMPLED.addAndGet(c[1]);
        NOISE_NS.addAndGet(c[2]);
        c[0] = 0; c[1] = 0; c[2] = 0;
    }

    /** Pulls this thread's pending counters into the totals. Cheap, safe to call often. */
    public static void flushAll() { flush(NOISE_TL.get()); }

    public static long noiseCalls() { return NOISE_CALLS.get(); }

    /** Mean nanoseconds per noise channel evaluation, or -1 if nothing sampled yet. */
    public static double noiseNsPerCall() {
        long s = NOISE_SAMPLED.get();
        return s == 0 ? -1.0 : NOISE_NS.get() / (double) s;
    }

    public static String noiseShare() {
        flushAll();
        long calls = NOISE_CALLS.get();
        double per = noiseNsPerCall();
        return "noise_calls=" + calls
                + " per_call_ns=" + (per < 0 ? "?" : String.format(java.util.Locale.ROOT, "%.0f", per))
                + " noise_us~" + (per < 0 ? "?" : (long) (calls * per / 1000.0));
    }

    public static void resetNoiseCounters() {
        flushAll();
        NOISE_CALLS.set(0); NOISE_SAMPLED.set(0); NOISE_NS.set(0);
    }

    // --------------------------------------------- vanilla channel parameters

    /** Parameters copied verbatim from data/minecraft/worldgen/noise/*.json. */
    public static NormalNoise continentalness(long seed) {
        return new NormalNoise(seed, -9, 0.8880832896205223, new double[]{1,1,2,2,2,1,1,1,1});
    }

    public static NormalNoise erosion(long seed) {
        return new NormalNoise(seed, -9, 1.063180125160734, new double[]{1,1,0,1,1});
    }

    public static NormalNoise temperature(long seed) {
        return new NormalNoise(seed, -10, 1.2453007926713473, new double[]{1.5,0,1,0,0,0});
    }

    public static String describe() {
        return "noise primitives: Xoroshiro128++ / PerlinNoise / NormalNoise (vanilla params)";
    }
}
