package bslsjdk.mcjavanpu;

import java.util.HashMap;
import java.util.Map;

/**
 * An executable form of vanilla's DensityFunction tree.
 *
 * Vanilla describes terrain as a tree of small pure functions (add / mul / noise / spline / ...)
 * loaded from JSON. This class rebuilds that tree in a form we control, so the maths stays
 * vanilla while the evaluation strategy is ours: today it runs on the CPU, and the leaf nodes
 * are shaped so a batch of them can be handed to the NPU later.
 *
 * Every node is a function of (x, y, z) only - no object graph, no registry, no Minecraft
 * classes - which is what makes it possible to evaluate a whole chunk in a tight loop.
 */
public abstract class NpuDf {

    public abstract double get(double x, double y, double z);

    /** Clears per-node caches; called between chunks so caches never leak across. */
    public void reset() {}

    // ------------------------------------------------------------- leaves

    public static NpuDf constant(final double v) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) { return v; }
            @Override public String toString() { return "const(" + v + ")"; }
        };
    }

    /** yClampedGradient: linearly interpolates a value between two heights. */
    public static NpuDf yClampedGradient(final int fromY, final int toY,
                                         final double fromValue, final double toValue) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) {
                double t = (y - fromY) / (double) (toY - fromY);
                if (t < 0) t = 0; else if (t > 1) t = 1;
                return fromValue + t * (toValue - fromValue);
            }
            @Override public String toString() { return "yGrad(" + fromY + "," + toY + ")"; }
        };
    }

    /** A noise channel with optional xz / y scale, exactly like vanilla's noise node. */
    public static NpuDf noise(final NpuNoise.NormalNoise n, final double xzScale, final double yScale) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) {
                return n.getValue(x * xzScale, y * yScale, z * xzScale);
            }
            @Override public String toString() { return "noise(xz=" + xzScale + ",y=" + yScale + ")"; }
        };
    }

    // ------------------------------------------------------------ unary

    public interface Unary { double apply(double v); }

    public static NpuDf unary(final NpuDf in, final Unary f, final String name) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) { return f.apply(in.get(x, y, z)); }
            @Override public void reset() { in.reset(); }
            @Override public String toString() { return name + "(" + in + ")"; }
        };
    }

    /** vanilla: v > 0 ? v : v * 0.25 */
    public static NpuDf quarterNegative(NpuDf in) {
        return unary(in, v -> v > 0 ? v : v * 0.25, "quarterNegative");
    }

    /** vanilla: v > 0 ? v : v * 0.5 */
    public static NpuDf halfNegative(NpuDf in) {
        return unary(in, v -> v > 0 ? v : v * 0.5, "halfNegative");
    }

    public static NpuDf clamp(NpuDf in, final double lo, final double hi) {
        return unary(in, v -> v < lo ? lo : (v > hi ? hi : v), "clamp");
    }

    public static NpuDf abs(NpuDf in) { return unary(in, Math::abs, "abs"); }
    public static NpuDf square(NpuDf in) { return unary(in, v -> v * v, "square"); }
    public static NpuDf cube(NpuDf in) { return unary(in, v -> v * v * v, "cube"); }

    // ----------------------------------------------------------- binary

    public interface Binary { double apply(double a, double b); }

    public static NpuDf binary(final NpuDf a, final NpuDf b, final Binary f, final String name) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) {
                return f.apply(a.get(x, y, z), b.get(x, y, z));
            }
            @Override public void reset() { a.reset(); b.reset(); }
            @Override public String toString() { return name + "(" + a + "," + b + ")"; }
        };
    }

    public static NpuDf add(NpuDf a, NpuDf b) { return binary(a, b, (p, q) -> p + q, "add"); }
    public static NpuDf sub(NpuDf a, NpuDf b) { return binary(a, b, (p, q) -> p - q, "sub"); }
    public static NpuDf mul(NpuDf a, NpuDf b) { return binary(a, b, (p, q) -> p * q, "mul"); }
    public static NpuDf min(NpuDf a, NpuDf b) { return binary(a, b, Math::min, "min"); }
    public static NpuDf max(NpuDf a, NpuDf b) { return binary(a, b, Math::max, "max"); }

    // ------------------------------------------------------------ cache

    /**
     * vanilla's cache node: memoises the last computed position.
     *
     * Cheap here, but the reason it exists matters for us: it means the same subtree is often
     * queried repeatedly at one point, so a batched NPU implementation may be able to reuse an
     * intermediate instead of recomputing it per sample.
     */
    public static NpuDf cache(final NpuDf in) {
        return new NpuDf() {
            private double lx = Double.NaN, ly, lz, lv;
            @Override public double get(double x, double y, double z) {
                if (x == lx && y == ly && z == lz) return lv;
                lx = x; ly = y; lz = z;
                lv = in.get(x, y, z);
                return lv;
            }
            @Override public void reset() { lx = Double.NaN; in.reset(); }
            @Override public String toString() { return "cache(" + in + ")"; }
        };
    }

    /**
     * slopedCheese structure, assembled by hand from
     * data/minecraft/worldgen/density_function/overworld/*.json.
     *
     *   slopedCheese = add( mul( quarterNegative( mul( add(depth, jaggedTerm), factor ) ), 4 ), base3d )
     *   jaggedTerm  = mul( jaggedness, halfNegative( noise(jagged, xz=1500, y=0) ) )
     */
    public static NpuDf slopedCheese(NpuDf depth, NpuDf factor, NpuDf jaggedness,
                                     NpuNoise.NormalNoise jagged, NpuDf base3d) {
        NpuDf jaggedTerm = mul(jaggedness, halfNegative(noise(jagged, 1500.0, 0.0)));
        NpuDf inner = add(depth, cache(jaggedTerm));
        return cache(add(mul(quarterNegative(mul(inner, factor)), constant(4.0)), base3d));
    }

    /** Recursively counts leaves, to show how much work one sample represents. */
    public int leafCount() { return 1; }

    /** Collects noise leaves so a batched evaluator can find them. */
    public void collectNoise(Map<String, NpuNoise.NormalNoise> out, String path) {}


    // ---------------------------------------------------------------- spline

    /**
     * Cubic Hermite spline, the shape vanilla uses for factor / offset / jaggedness.
     *
     * controlX / controlY / controlD hold the knot locations, values and derivatives. Outside
     * the knot range the spline is flat. Inside, the segment is the standard Hermite form, which
     * is why the third-order term appears in the interpolation below.
     */
    public static final class Spline {
        private final double[] x, y, d;
        private final double[] a, b;   // precomputed cubic coefficients

        public Spline(double[] xs, double[] ys, double[] ds) {
            this.x = xs; this.y = ys; this.d = ds;
            int n = xs.length - 1;
            this.a = new double[n];
            this.b = new double[n];
            for (int i = 0; i < n; i++) {
                double dx = xs[i + 1] - xs[i];
                double dy = ys[i + 1] - ys[i];
                a[i] = d[i] * dx - dy;
                b[i] = -d[i + 1] * dx + dy;
            }
        }

        public double apply(double v) {
            int last = x.length - 1;
            if (v <= x[0]) return y[0];
            if (v >= x[last]) return y[last];
            int i = 0;
            while (i < last - 1 && v >= x[i + 1]) i++;
            double dx = x[i + 1] - x[i];
            double t = (v - x[i]) / dx;
            double base = y[i] + t * (y[i + 1] - y[i]);
            double cubic = t * (1 - t) * (a[i] * (1 - t) + b[i] * t);
            return base + cubic;
        }
    }

    /** A spline node driven by another density function (usually continents / erosion / ridges). */
    public static NpuDf spline(final NpuDf coordinate, final Spline spline) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) {
                return spline.apply(coordinate.get(x, y, z));
            }
            @Override public void reset() { coordinate.reset(); }
            @Override public String toString() { return "spline"; }
        };
    }

    // ------------------------------------------------------------ gradient

    /** vanilla gradient node: a linear ramp along one axis, clamped outside the range. */
    public static NpuDf gradientY(final double fromCoord, final double fromValue,
                                  final double toCoord, final double toValue) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) {
                double t = (y - fromCoord) / (toCoord - fromCoord);
                if (t < 0) t = 0; else if (t > 1) t = 1;
                return fromValue + t * (toValue - fromValue);
            }
            @Override public String toString() { return "gradientY"; }
        };
    }

    /** vanilla lerp: mix two constant/child functions by a clamped alpha. */
    public static NpuDf lerp(final NpuDf alpha, final NpuDf first, final NpuDf second) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) {
                double t = alpha.get(x, y, z);
                if (t < 0) t = 0; else if (t > 1) t = 1;
                return first.get(x, y, z) + t * (second.get(x, y, z) - first.get(x, y, z));
            }
            @Override public void reset() { alpha.reset(); first.reset(); second.reset(); }
            @Override public String toString() { return "lerp"; }
        };
    }

    /** vanilla range_choice: pick a branch depending on whether the input falls in a window. */
    public static NpuDf rangeChoice(final NpuDf input, final double minInclusive, final double maxExclusive,
                                    final NpuDf inRange, final NpuDf outOfRange) {
        return new NpuDf() {
            @Override public double get(double x, double y, double z) {
                double v = input.get(x, y, z);
                return (v >= minInclusive && v < maxExclusive)
                        ? inRange.get(x, y, z) : outOfRange.get(x, y, z);
            }
            @Override public void reset() { input.reset(); inRange.reset(); outOfRange.reset(); }
            @Override public String toString() { return "rangeChoice"; }
        };
    }

    /** vanillasqueeze: pulls values towards [-1, 1] so the written density stays sane. */
    public static NpuDf squeeze(NpuDf in) {
        return unary(in, v -> {
            double c = v < -1 ? -1 : (v > 1 ? 1 : v);
            return c / 2.0 - c * c * c / 24.0;
        }, "squeeze");
    }

    /**
     * Counts the noise leaves under this node, and sums the per-sample dot-product width.
     * This is the number that tells us how much work a batched NPU path would carry.
     */
    public static final class LeafStats {
        public int noiseLeaves;
        public int totalOctaves;
    }
    @Override public String toString() { return "df"; }
}
