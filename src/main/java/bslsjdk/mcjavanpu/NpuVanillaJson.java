package bslsjdk.mcjavanpu;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads vanilla worldgen JSON straight out of the game jar.
 *
 * The terrain parameters live in data/minecraft/worldgen/... inside the jar. Reading them at
 * runtime instead of copying numbers into source means the parameters can never drift from the
 * version being played, and a datapack override is a future possibility rather than a rewrite.
 */
public final class NpuVanillaJson {

    private static ZipFile jar;
    private static String jarPath;

    private NpuVanillaJson() {}

    private static synchronized ZipFile jar() {
        if (jar != null) return jar;
        try {
            // locate the jar that actually contains Minecraft, not the mod jar
            Class<?> mc = Class.forName("net.minecraft.world.level.Level");
            URI uri = mc.getProtectionDomain().getCodeSource().getLocation().toURI();
            jarPath = new File(uri).getAbsolutePath();
            jar = new ZipFile(jarPath);
        } catch (Throwable t) {
            jarPath = "unavailable: " + t;
        }
        return jar;
    }

    public static String jarLocation() { jar(); return jarPath; }

    /** Returns the file's text, or null when missing. */
    public static String read(String path) {
        ZipFile z = jar();
        if (z == null) return null;
        try {
            ZipEntry e = z.getEntry(path);
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** Shorthand for the noise parameter file of a named channel. */
    public static String noiseParams(String name) {
        return read("data/minecraft/worldgen/noise/" + name + ".json");
    }

    /** Shorthand for an overworld density function file. */
    public static String densityFunction(String name) {
        return read("data/minecraft/worldgen/density_function/overworld/" + name + ".json");
    }

    public static boolean available() { return jar() != null; }
}
