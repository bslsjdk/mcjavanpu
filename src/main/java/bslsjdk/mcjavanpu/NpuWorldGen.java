package bslsjdk.mcjavanpu;

import java.util.Locale;

/**
 * Our replacement world generator, assembled from vanilla's own definitions.
 *
 * The plan is for this to be able to run in two modes:
 *
 *   ASSIST - only the noise leaves are computed in bulk (NPU once wired up) and the rest of
 *            vanilla's tree still runs, so vanilla keeps authoring the result but gets its
 *            inputs faster.
 *   TAKE   - the whole tree evaluated here and vanilla's generator is never asked, so this
 *            code alone decides the terrain.
 *
 * Both modes share everything below, which is why correctness here matters more than speed:
 * if the tree does not agree with vanilla, both modes are wrong. The self test reports how much
 * of the vanilla graph was reconstructed and how the density field behaves at real coordinates.
 */
public final class NpuWorldGen {

    private NpuWorldGen() {}

    /** Density at one block position, through the reconstructed offset/depth chain. */
    public static final class Probe {
        public double offset, depth, density;
    }

    public static String selftest(long seed) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "worldgen selftest seed=%d%n", seed));
        sb.append("jar: ").append(NpuVanillaJson.jarLocation()).append('\n');
        if (!NpuVanillaJson.available()) {
            sb.append("vanilla jar not reachable, cannot build the tree\n");
            return sb.toString();
        }

        String offsetJson = NpuVanillaJson.densityFunction("offset");
        String depthJson = NpuVanillaJson.densityFunction("depth");
        String slopedJson = NpuVanillaJson.densityFunction("sloped_cheese");
        sb.append("offset.json=").append(offsetJson == null ? "missing" : offsetJson.length() + "B")
          .append(" depth.json=").append(depthJson == null ? "missing" : depthJson.length() + "B")
          .append(" sloped_cheese.json=").append(slopedJson == null ? "missing" : slopedJson.length() + "B")
          .append('\n');

        NpuDfJson.Build ob = offsetJson == null ? null : NpuDfJson.buildTree(offsetJson, seed);
        NpuDfJson.Build db = depthJson == null ? null : NpuDfJson.buildTree(depthJson, seed);
        NpuDfJson.Build sbb = slopedJson == null ? null : NpuDfJson.buildTree(slopedJson, seed);

        if (ob != null) sb.append("offset  ").append(ob.summary()).append('\n');
        if (db != null) sb.append("depth   ").append(db.summary()).append('\n');
        if (sbb != null) sb.append("sloped  ").append(sbb.summary()).append('\n');

        // Evaluate a column profile: how the density behaves from bedrock to build limit.
        if (sbb != null) {
            sb.append("column at (0,0), sloped_cheese density by y:\n");
            double[] ys = {-64, -32, 0, 32, 64, 96, 128, 160, 200, 256, 320};
            for (double y : ys) {
                double v = sbb.root.get(0, y, 0);
                sb.append(String.format(Locale.ROOT, "  y=%-5.0f density=%+.4f %s%n", y, v, v > 0 ? "SOLID" : "air"));
            }
            // A short surface scan: highest y where density turns positive across a few columns
            sb.append("surface scan:\n");
            for (int cx = -2; cx <= 2; cx++) {
                for (int cz = -2; cz <= 2; cz += 2) {
                    double x = cx * 16, z = cz * 16;
                    int top = Integer.MIN_VALUE;
                    for (int y = 320; y >= -64; y -= 4) {
                        if (sbb.root.get(x, y, z) > 0) { top = y; break; }
                    }
                    sb.append(String.format(Locale.ROOT, "  (%4.0f,%4.0f) top=%d%n", x, z, top));
                }
            }
        }
        return sb.toString();
    }
}
