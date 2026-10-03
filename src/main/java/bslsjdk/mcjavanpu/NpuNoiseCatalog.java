package bslsjdk.mcjavanpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Named noise channels, built from vanilla's own parameter files.
 *
 * Each channel (continentalness, erosion, cave_cheese, ...) is described by a small JSON file
 * carrying base_octave / base_amplitude / octave_count / amplitude_modifiers. Reading it here
 * means the noise shape is vanilla's by construction, which is the whole point: we change who
 * computes the value, never what the value means.
 */
public final class NpuNoiseCatalog {

    private static final Map<String, NpuNoise.NormalNoise> CACHE = new HashMap<>();

    private NpuNoiseCatalog() {}

    public static synchronized NpuNoise.NormalNoise get(String name, long seed) {
        String key = name + "@" + seed;
        NpuNoise.NormalNoise hit = CACHE.get(key);
        if (hit != null) return hit;

        NpuNoise.NormalNoise built = build(name, seed);
        CACHE.put(key, built);
        return built;
    }

    private static NpuNoise.NormalNoise build(String name, long seed) {
        String json = NpuVanillaJson.noiseParams(name);
        if (json == null) {
            // fall back to a continentalness-shaped channel rather than crashing the world
            return NpuNoise.continentalness(seed);
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            int baseOctave = (int) Math.floor(o.get("base_octave").getAsDouble());
            double baseAmplitude = o.get("base_amplitude").getAsDouble();
            int octaveCount = (int) Math.floor(o.get("octave_count").getAsDouble());
            JsonArray mods = o.getAsJsonArray("amplitude_modifiers");
            double[] modifiers = new double[octaveCount];
            for (int i = 0; i < octaveCount; i++) {
                modifiers[i] = i < mods.size() ? mods.get(i).getAsDouble() : 0.0;
            }
            return new NpuNoise.NormalNoise(seed, baseOctave, baseAmplitude, modifiers);
        } catch (Exception e) {
            return NpuNoise.continentalness(seed);
        }
    }

    public static synchronized int cached() { return CACHE.size(); }

    public static synchronized void clear() { CACHE.clear(); }
}
