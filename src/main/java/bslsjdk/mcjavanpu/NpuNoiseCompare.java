package bslsjdk.mcjavanpu;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.Noise;

import java.util.Locale;

/**
 * Stage 3 of the plan: measure the gap between our noise and vanilla instead of guessing.
 *
 * Vanilla's own NormalNoise is built here from the same JSON parameters and the same seed, so
 * the comparison is against the real generator, not against a hand-written expectation. For a
 * fixed seed and coordinate set this reports max / mean / RMSE of the difference along with how
 * many samples exceed a tolerance, which is the number that decides whether the NPU path is
 * allowed to stand in for vanilla.
 */
public final class NpuNoiseCompare {

    private NpuNoiseCompare() {}

    public static final class Stats {
        public int n;
        public double maxAbs, meanAbs, rmse, badFrac, vanillaRange, mineRange;

        public String line(String label) {
            return String.format(Locale.ROOT,
                "%-14s n=%d max=%.6f mean=%.6f rmse=%.6f bad=%.1f%% vanillaAbsMax=%.4f mineAbsMax=%.4f",
                label, n, maxAbs, meanAbs, rmse, badFrac * 100.0, vanillaRange, mineRange);
        }
    }

    /** Compares one channel over a deterministic coordinate sweep. */
    private static Stats compare(Noise vanilla, NpuNoise.NormalNoise mine, int samples, double scale) {
        Stats s = new Stats();
        s.n = samples;
        double sum = 0, sum2 = 0;
        for (int i = 0; i < samples; i++) {
            // spread the samples over a chunk-sized neighbourhood but walk far enough to
            // cross several octave cells, otherwise every sample lands in the same lattice cell
            double x = ((i % 64) - 32) * 16.0 * scale;
            double z = (((i / 64) % 64) - 32) * 16.0 * scale;
            double y = ((i / 4096) % 16) * 24.0;

            double a = vanilla.get(x, y, z);
            double b = mine.getValue(x, y, z);
            double d = Math.abs(a - b);
            if (d > s.maxAbs) s.maxAbs = d;
            sum += d;
            sum2 += d * d;
            if (d > 0.05) s.badFrac += 1;
            if (Math.abs(a) > s.vanillaRange) s.vanillaRange = Math.abs(a);
            if (Math.abs(b) > s.mineRange) s.mineRange = Math.abs(b);
        }
        s.meanAbs = sum / samples;
        s.rmse = Math.sqrt(sum2 / samples);
        s.badFrac /= samples;
        return s;
    }

    public static String run(long seed, int samples) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "noise compare seed=%d samples=%d%n", seed, samples));

        // continentalness: baseOctave -9, 9 octaves, amplitude 0.8880832896205223
        NormalNoise.Builder cb = NormalNoise.builder()
                .setBaseAmplitude(0.8880832896205223)
                .setBaseOctave(-9)
                .setOctaveCount(9);
        double[] cmods = {1, 1, 2, 2, 2, 1, 1, 1, 1};
        for (int i = 0; i < cmods.length; i++) cb.setAmplitudeModifier(i, cmods[i]);
        Noise cVanilla = cb.build().create(RandomSource.create(seed));
        sb.append(compare(cVanilla, NpuNoise.continentalness(seed), samples, 0.25).line("continentalness")).append('\n');

        // erosion: -9, 5 octaves
        NormalNoise.Builder eb = NormalNoise.builder()
                .setBaseAmplitude(1.063180125160734)
                .setBaseOctave(-9)
                .setOctaveCount(5);
        double[] emods = {1, 1, 0, 1, 1};
        for (int i = 0; i < emods.length; i++) eb.setAmplitudeModifier(i, emods[i]);
        Noise eVanilla = eb.build().create(RandomSource.create(seed));
        sb.append(compare(eVanilla, NpuNoise.erosion(seed), samples, 0.25).line("erosion")).append('\n');

        // temperature: -10, 6 octaves
        NormalNoise.Builder tb = NormalNoise.builder()
                .setBaseAmplitude(1.2453007926713473)
                .setBaseOctave(-10)
                .setOctaveCount(6);
        double[] tmods = {1.5, 0, 1, 0, 0, 0};
        for (int i = 0; i < tmods.length; i++) tb.setAmplitudeModifier(i, tmods[i]);
        Noise tVanilla = tb.build().create(RandomSource.create(seed));
        sb.append(compare(tVanilla, NpuNoise.temperature(seed), samples, 0.25).line("temperature")).append('\n');

        return sb.toString();
    }
}
