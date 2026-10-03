package bslsjdk.mcjavanpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sampling profiler. Answers the only question that matters before optimising:
 * where does the frame time actually go?
 *
 * It samples every live thread's top frame a few hundred times a second and counts
 * hits. No bytecode rewriting, no hooks into game code, nothing that can break a
 * world - which is deliberate, because a profiler that alters what it measures is
 * worse than useless.
 *
 * Targets only the threads that can be a bottleneck: the server tick, the render
 * thread and the worker pools. Anything else is noise.
 */
public final class NpuProfiler {

    private NpuProfiler() {}

    public static final class Hit {
        public final String frame;
        public final long samples;
        public final Map<String, Long> threads = new HashMap<>();
        Hit(String f) { this.frame = f; this.samples = 0; }
        Hit(String f, long s) { this.frame = f; this.samples = s; }
    }

    private static volatile boolean running;

    public static boolean isRunning() { return running; }

    /** Samples for `seconds`, then writes a ranked list to the dedicated log. */
    public static String sample(final int seconds) {
        if (running) return "already running";
        running = true;
        final long end = System.currentTimeMillis() + seconds * 1000L;
        final Map<String, long[]> counts = new HashMap<>();
        final Map<String, Map<String, Long>> perThread = new HashMap<>();
        final java.util.concurrent.atomic.AtomicLong samples = new java.util.concurrent.atomic.AtomicLong();

        Thread t = new Thread(() -> {
            while (System.currentTimeMillis() < end) {
                Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
                for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                    String name = e.getKey().getName();
                    if (!interesting(name)) continue;
                    StackTraceElement[] st = e.getValue();
                    if (st == null || st.length == 0) continue;
                    String frame = shortName(st[0]);
                    synchronized (counts) {
                        counts.computeIfAbsent(frame, k -> new long[1])[0]++;
                        perThread.computeIfAbsent(frame, k -> new HashMap<>())
                                 .merge(name, 1L, Long::sum);
                    }
                }
                samples.incrementAndGet();
                try { Thread.sleep(3); } catch (InterruptedException ie) { break; }
            }
            running = false;
        }, "mcjavanpu-profiler");
        t.setDaemon(true);
        t.start();

        try { t.join(seconds * 1000L + 2000L); } catch (InterruptedException ignored) { }

        List<Map.Entry<String, long[]>> ranked = new ArrayList<>();
        synchronized (counts) { ranked.addAll(counts.entrySet()); }
        ranked.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));

        StringBuilder sb = new StringBuilder();
        sb.append("profile over ").append(seconds).append("s, ").append(samples.get()).append(" passes");
        long total = 0;
        for (Map.Entry<String, long[]> e : ranked) total += e.getValue()[0];
        sb.append(", ").append(total).append(" hits\n");
        int shown = 0;
        for (Map.Entry<String, long[]> e : ranked) {
            if (shown++ >= 25) break;
            long h = e.getValue()[0];
            double pct = total == 0 ? 0 : h * 100.0 / total;
            Map<String, Long> th = perThread.get(e.getKey());
            String top = "";
            if (th != null) {
                long best = -1;
                for (Map.Entry<String, Long> t2 : th.entrySet()) if (t2.getValue() > best) { best = t2.getValue(); top = t2.getKey(); }
            }
            sb.append(String.format(java.util.Locale.ROOT, "%6.2f%%  %-6d  %-58s  [%s]\n", pct, h, e.getKey(), top));
        }
        NpuLog.log(sb.toString());
        return sb.toString();
    }

    private static boolean interesting(String n) {
        return n.contains("Server thread") || n.contains("Render thread")
            || n.startsWith("Worker-Main") || n.startsWith("Worker-") || n.contains("main");
    }

    private static String shortName(StackTraceElement e) {
        String c = e.getClassName();
        int i = c.lastIndexOf('.');
        if (i > 0) c = c.substring(i + 1);
        return c + "." + e.getMethodName();
    }
}
