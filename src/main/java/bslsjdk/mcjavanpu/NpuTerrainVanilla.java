package bslsjdk.mcjavanpu;

/**
 * Terrain density from the real vanilla tree, not from a hand-written approximation.
 *
 * This exists because the previous lattice generator invented its own maths - a pile of sin/cos and
 * triangle waves that produces something terrain-shaped but is NOT vanilla's terrain. Same seed, same
 * coordinates, different world. That makes the takeover path worthless for anything except a demo:
 * the whole point is vanilla's result, computed a different way.
 *
 * So the maths here comes from the game itself. Minecraft ships every density function as data:
 *
 *   data/minecraft/worldgen/density_function/overworld/final_density.json
 *
 * NpuVanillaJson reads it out of the jar, NpuDfJson compiles it into an executable tree, and this
 * class evaluates that tree. Nothing about the terrain shape is decided here - we only decide where
 * to evaluate and how to interpolate, which is exactly the split the project is based on:
 *
 *   vanilla defines what it looks like; we define how it is computed.
 *
 * Sampling resolution follows the volume the game asks for (its own stepX/stepY/stepZ), never a
 * resolution we prefer for the NPU. Changing that would be changing world generation.
 */
public final class NpuTerrainVanilla {

    /** The compiled vanilla tree. Rebuilt only when the seed changes. */
    private static volatile NpuDf tree;
    private static volatile long treeSeed = Long.MIN_VALUE;
    private static volatile String failReason = "";
    private static final java.util.concurrent.atomic.AtomicLong SAMPLES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong EVAL_US =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The same tree, lowered into a flat instruction stream (see NpuDfProgram).
     *
     * Null means lowering failed and the tree walk is used instead - slower, same answer.
     */
    private static volatile NpuDfProgram program;

    /** One register file per thread so sampling never allocates inside the loop. */
    private static final ThreadLocal<double[]> REGS =
            ThreadLocal.withInitial(() -> new double[64]);

    private NpuTerrainVanilla() {}

    public static String failReason() { return failReason; }

    /** True when sampling runs the compiled program rather than walking the tree. */
    public static boolean lowered() { return program != null; }

    public static int programInstructions() { return program == null ? -1 : program.instructions(); }

    public static boolean ready() { return tree != null; }

    public static String summary() {
        long n = SAMPLES.get();
        return "vanilla_tree=" + (tree != null ? "ready" : "no(" + failReason + ")")
                + " samples=" + n
                + " eval_ms=" + (EVAL_US.get() / 1000)
                + " per_sample_us=" + (n == 0 ? 0 : EVAL_US.get() / n)
                + " prog_insn=" + (program == null ? -1 : program.instructions())
                + " prog_regs=" + (program == null ? -1 : program.registers());
    }

    /**
     * Compiles final_density (which pulls in its whole dependency chain) for this seed.
     *
     * Vanilla's own entry point for the overworld is "final_density": it already contains the
     * sloped_cheese / depth / factor / jaggedness structure and the interpolated wrapper.
     */
    public static synchronized NpuDf tree(long seed) {
        if (tree != null && treeSeed == seed) return tree;
        try {
            if (!NpuVanillaJson.available()) { failReason = "jar not found"; return null; }
            String json = NpuVanillaJson.densityFunction("final_density");
            if (json == null || json.isEmpty()) { failReason = "final_density missing"; return null; }
            NpuDfJson.Build b = NpuDfJson.buildTree(json, seed);
            if (b == null || b.root == null) { failReason = "tree build returned null"; return null; }
            tree = b.root;
            treeSeed = seed;
            failReason = "";
            try {
                program = NpuDfProgram.build(b.root);
                NpuLog.log("vanilla density tree ready | instructions=" + program.instructions()
                        + " registers=" + program.registers()
                        + " folded_constants=" + program.constants()
                        + " | " + b.root);
            } catch (Throwable lower) {
                program = null;
                NpuLog.error("density lowering failed, falling back to tree walk", lower);
            }
            return tree;
        } catch (Throwable t) {
            failReason = String.valueOf(t);
            NpuLog.error("vanilla density tree build failed", t);
            return null;
        }
    }

    /**
     * Fills a chunk volume with vanilla density.
     *
     * Evaluation happens on the lattice the game itself uses (stepX/stepY/stepZ from the volume),
     * then trilinear interpolation fills the rest - which is precisely what vanilla does, and why
     * the answer matches. Returns null when the tree is unavailable so the caller can fall back to
     * the game instead of shipping a wrong world.
     *
     * Output order matches DensityBuffer: z outer, x middle, y inner.
     */
    public static float[] fill(int sx, int sy, int sz, int ox, int oy, int oz, long seed,
                               int stepX, int stepY, int stepZ) {
        NpuDf t = tree(seed);
        if (t == null) return null;
        if (stepX < 1) stepX = 1;
        if (stepY < 1) stepY = 1;
        if (stepZ < 1) stepZ = 1;

        int lx = (sx - 1) / stepX + 2;   // lattice points needed to cover sx samples
        int ly = (sy - 1) / stepY + 2;
        int lz = (sz - 1) / stepZ + 2;

        NpuDfProgram p = program;
        double[] regs = REGS.get();
        if (p != null && regs.length < p.registers()) {
            regs = new double[p.registers()];
            REGS.set(regs);
        }

        long t0 = System.nanoTime();
        float[] lat = new float[lx * ly * lz];
        t.reset();
        int li = 0;
        for (int iy = 0; iy < ly; iy++) {
            double wy = oy + (double) iy * stepY;
            for (int iz = 0; iz < lz; iz++) {
                double wz = oz + (double) iz * stepZ;
                for (int ix = 0; ix < lx; ix++) {
                    double wx = ox + (double) ix * stepX;
                    lat[li++] = (float) (p != null ? p.eval(wx, wy, wz, regs) : t.get(wx, wy, wz));
                }
            }
        }
        long evUs = (System.nanoTime() - t0) / 1000;
        SAMPLES.addAndGet((long) lx * ly * lz);
        EVAL_US.addAndGet(evUs);

        float[] out = new float[sx * sy * sz];
        int oi = 0;
        for (int z = 0; z < sz; z++) {
            int iz = z / stepZ;
            float fz = (z % stepZ) / (float) stepZ;
            for (int x = 0; x < sx; x++) {
                int ix = x / stepX;
                float fx = (x % stepX) / (float) stepX;
                for (int y = 0; y < sy; y++) {
                    int iy = y / stepY;
                    float fy = (y % stepY) / (float) stepY;
                    float v000 = L(lat, lx, ly, lz, ix, iy, iz);
                    float v100 = L(lat, lx, ly, lz, ix + 1, iy, iz);
                    float v010 = L(lat, lx, ly, lz, ix, iy + 1, iz);
                    float v110 = L(lat, lx, ly, lz, ix + 1, iy + 1, iz);
                    float v001 = L(lat, lx, ly, lz, ix, iy, iz + 1);
                    float v101 = L(lat, lx, ly, lz, ix + 1, iy, iz + 1);
                    float v011 = L(lat, lx, ly, lz, ix, iy + 1, iz + 1);
                    float v111 = L(lat, lx, ly, lz, ix + 1, iy + 1, iz + 1);
                    float x00 = v000 + fx * (v100 - v000);
                    float x10 = v010 + fx * (v110 - v010);
                    float x01 = v001 + fx * (v101 - v001);
                    float x11 = v011 + fx * (v111 - v011);
                    float y0 = x00 + fy * (x10 - x00);
                    float y1 = x01 + fy * (x11 - x01);
                    out[oi++] = y0 + fz * (y1 - y0);
                }
            }
        }
        return out;
    }

    private static float L(float[] lat, int lx, int ly, int lz, int ix, int iy, int iz) {
        if (ix >= lx) ix = lx - 1;
        if (iy >= ly) iy = ly - 1;
        if (iz >= lz) iz = lz - 1;
        return lat[(iy * lz + iz) * lx + ix];
    }
}
