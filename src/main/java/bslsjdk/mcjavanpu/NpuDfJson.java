package bslsjdk.mcjavanpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds an executable NpuDf tree from vanilla's worldgen JSON.
 *
 * This is the bridge that lets us drop vanilla's evaluator without changing the terrain: the
 * structure (which node feeds which) comes from the JSON, the parameters come from the JSON,
 * and only the evaluation strategy is ours. A referenced file such as
 * "minecraft:overworld/offset" is resolved by reading that file and building it too.
 *
 * Unsupported node types degrade to a zero node and are counted, so a missing type shows up as
 * a number in the report instead of as silently wrong terrain.
 */
public final class NpuDfJson {

    /**
     * Unsupported node count from the most recent build.
     *
     * Kept statically because the terrain gate needs it at write time, long after
     * the Build object is gone. Every unsupported node is silently turned into a
     * constant 0, so a tree that "built fine" can still be quietly wrong - this
     * is the number that says so. -1 means no build has happened yet.
     */
    private static volatile int lastUnsupported = -1;
    private static volatile String lastTypes = "";

    public static int lastUnsupported() { return lastUnsupported; }
    public static String lastUnsupportedTypes() { return lastTypes; }

    private static void noteUnsupported(int n, Map<String, Integer> types) {
        lastUnsupported = n;
        lastTypes = String.valueOf(types);
    }

    private NpuDfJson() {}

    public static final class Build {
        public NpuDf root;
        public int resolved;
        public int unsupported;
        public final Map<String, Integer> unsupportedTypes = new HashMap<>();
        public final Map<String, NpuDf> noiseNodes = new HashMap<>();

        public String summary() {
            return "df tree: resolved=" + resolved + " unsupported=" + unsupported
                    + (unsupportedTypes.isEmpty() ? "" : " " + unsupportedTypes)
                    + " noiseNodes=" + noiseNodes.size();
        }
    }

    private static String strip(String id) {
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    /** Resolves a string reference like "minecraft:overworld/offset" to a built tree. */
    private static NpuDf reference(String id, long seed, Build b, Map<String, NpuDf> memo) {
        String key = strip(id);
        NpuDf cached = memo.get(key);
        if (cached != null) return cached;

        String json = key.startsWith("overworld/")
                ? NpuVanillaJson.densityFunction(key.substring("overworld/".length()))
                : NpuVanillaJson.read("data/minecraft/worldgen/density_function/" + key + ".json");
        if (json == null) {
            b.unsupported++;
            b.unsupportedTypes.merge("missing:" + key, 1, Integer::sum);
            noteUnsupported(b.unsupported, b.unsupportedTypes);
            return NpuDf.constant(0.0);
        }
        NpuDf built = build(json, seed, b, memo);
        memo.put(key, built);
        return built;
    }

    public static Build buildTree(String rootJson, long seed) {
        Build b = new Build();
        lastUnsupported = 0; lastTypes = "";
        b.root = build(rootJson, seed, b, new HashMap<>());
        return b;
    }

    public static NpuDf build(String json, long seed, Build b, Map<String, NpuDf> memo) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            b.unsupported++;
            b.unsupportedTypes.merge("parse", 1, Integer::sum);
            noteUnsupported(b.unsupported, b.unsupportedTypes);
            return NpuDf.constant(0.0);
        }
        return buildObject(o, seed, b, memo);
    }

    private static NpuDf child(JsonObject o, String name, long seed, Build b, Map<String, NpuDf> memo) {
        JsonElement e = o.get(name);
        if (e == null) return NpuDf.constant(0.0);
        if (e.isJsonPrimitive()) return reference(e.getAsString(), seed, b, memo);
        if (e.isJsonObject()) return buildObject(e.getAsJsonObject(), seed, b, memo);
        if (e.isJsonArray()) {
            // vanilla allows a list where the members are summed
            NpuDf acc = null;
            for (JsonElement c : e.getAsJsonArray()) {
                NpuDf t = c.isJsonPrimitive() ? reference(c.getAsString(), seed, b, memo)
                                              : buildObject(c.getAsJsonObject(), seed, b, memo);
                acc = acc == null ? t : NpuDf.add(acc, t);
            }
            return acc == null ? NpuDf.constant(0.0) : acc;
        }
        return NpuDf.constant(0.0);
    }

    private static double num(JsonObject o, String name, double def) {
        JsonElement e = o.get(name);
        return e == null ? def : e.getAsDouble();
    }

    private static NpuDf buildObject(JsonObject o, long seed, Build b, Map<String, NpuDf> memo) {
        String type = strip(o.get("type").getAsString());
        b.resolved++;
        switch (type) {
            case "constant":
                return NpuDf.constant(num(o, "value", 0.0));
            case "add":
                return NpuDf.add(child(o, "left", seed, b, memo), child(o, "right", seed, b, memo));
            case "mul":
                return NpuDf.mul(child(o, "left", seed, b, memo), child(o, "right", seed, b, memo));
            case "min":
                return NpuDf.min(child(o, "left", seed, b, memo), child(o, "right", seed, b, memo));
            case "max":
                return NpuDf.max(child(o, "left", seed, b, memo), child(o, "right", seed, b, memo));
            case "abs":
                return NpuDf.abs(child(o, "input", seed, b, memo));
            case "square":
                return NpuDf.square(child(o, "input", seed, b, memo));
            case "cube":
                return NpuDf.cube(child(o, "input", seed, b, memo));
            case "half_negative":
                return NpuDf.halfNegative(child(o, "input", seed, b, memo));
            case "quarter_negative":
                return NpuDf.quarterNegative(child(o, "input", seed, b, memo));
            case "squeeze":
                return NpuDf.squeeze(child(o, "input", seed, b, memo));
            case "clamp":
                return NpuDf.clamp(child(o, "input", seed, b, memo), num(o, "min", -1), num(o, "max", 1));
            case "cache":
            case "flat_cache":
                return NpuDf.cache(child(o, "input", seed, b, memo));
            case "gradient":
                // only the y axis appears in overworld terrain
                return NpuDf.gradientY(num(o, "from_coordinate", 0), num(o, "from_value", 0),
                                       num(o, "to_coordinate", 1), num(o, "to_value", 0));
            case "y_clamped_gradient":
                return NpuDf.yClampedGradient((int) num(o, "from_y", -64), (int) num(o, "to_y", 320),
                                              num(o, "from_value", 0), num(o, "to_value", 1));
            case "lerp":
                return NpuDf.lerp(child(o, "alpha", seed, b, memo),
                                  child(o, "first", seed, b, memo),
                                  child(o, "second", seed, b, memo));
            case "range_choice":
                return NpuDf.rangeChoice(child(o, "input", seed, b, memo),
                                         num(o, "min_inclusive", -1e6), num(o, "max_exclusive", 1e6),
                                         child(o, "when_in_range", seed, b, memo),
                                         child(o, "when_out_of_range", seed, b, memo));
            case "noise": {
                String name = strip(o.get("noise").getAsString());
                double xz = num(o, "xz_scale", 1.0);
                double y = num(o, "y_scale", 1.0);
                b.noiseNodes.put(name, NpuDf.constant(0));
                return NpuDf.noise(NpuNoiseCatalog.get(name, seed), xz, y);
            }
            case "spline": {
                JsonObject sp = o.getAsJsonObject("spline");
                NpuDf coord = child(sp, "coordinate", seed, b, memo);
                JsonArray pts = sp.getAsJsonArray("points");
                int n = pts.size();
                double[] xs = new double[n], ys = new double[n], ds = new double[n];
                boolean numeric = true;
                for (int i = 0; i < n; i++) {
                    JsonObject pt = pts.get(i).getAsJsonObject();
                    xs[i] = pt.get("location").getAsDouble();
                    JsonElement val = pt.get("value");
                    if (val != null && val.isJsonPrimitive()) ys[i] = val.getAsDouble();
                    else numeric = false;              // nested spline as a knot value
                    JsonElement der = pt.get("derivative");
                    ds[i] = der != null && der.isJsonPrimitive() ? der.getAsDouble() : 0.0;
                }
                if (!numeric || n < 2) {
                    b.unsupported++;
                    b.unsupportedTypes.merge("spline:nested", 1, Integer::sum);
                    return NpuDf.constant(0.0);
                }
                return NpuDf.spline(coord, new NpuDf.Spline(xs, ys, ds));
            }
            default:
                b.unsupported++;
                b.unsupportedTypes.merge(type, 1, Integer::sum);
            noteUnsupported(b.unsupported, b.unsupportedTypes);
                return NpuDf.constant(0.0);
        }
    }
}
