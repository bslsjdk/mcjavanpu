package bslsjdk.mcjavanpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The vanilla density tree, lowered into a linear instruction stream.
 *
 * Why this exists. Walking the tree as objects costs one virtual call per node per sample, plus
 * recursion, plus pointer chasing - and a chunk needs a few thousand samples. Vanilla pays the
 * same price, which is exactly why density evaluation is the expensive part of chunk generation.
 *
 * C2ME's answer to that problem is the same shape as this class: compile the density function
 * into a flat form once, then run the flat form, instead of walking the object graph per sample.
 * We take the idea, not the code - our instruction set is written for what our tree actually
 * contains, and nothing here touches vanilla's classes.
 *
 * The maths is untouched. Every instruction is exactly one node of the vanilla tree; only the way
 * it is executed changed. Three cheap wins are taken at compile time and cost nothing at run time:
 *
 *1. constant folding - a subtree independent of x/y/z becomes a stored value *2. cache nodes vanish - a flat pass visits each node once per sample, so the memo cannot hit *3. x/y/z live in registers0..2 - no node allocates, nothing is boxed *
 * The instruction format is deliberately flat: four ints per instruction plus a shared pool for
 * immediate values and referenced leaves. One switch drives it.
 */
public final class NpuDfProgram {

    // ---- opcodes ---------------------------------------------------------------

    static final int OP_ADD = 0;
    static final int OP_SUB = 1;
    static final int OP_MUL = 2;
    static final int OP_MIN = 3;
    static final int OP_MAX = 4;
    static final int OP_NEG = 5;
    static final int OP_ABS = 6;
    static final int OP_SQUARE = 7;
    static final int OP_CUBE = 8;
    static final int OP_QUARTER_NEG = 9;
    static final int OP_HALF_NEG = 10;
    static final int OP_SQUEEZE = 11;
    static final int OP_CLAMP = 12;
    static final int OP_NOISE = 13;
    static final int OP_SPLINE = 14;
    static final int OP_YGRAD = 15;
    static final int OP_LERP = 16;
    static final int OP_IN_RANGE = 17;
    static final int OP_COPY = 18;
    static final int OP_JZ = 19;
    static final int OP_JMP = 20;

    private final int[] op;
    private final int[] ra;   // destination register, or jump target
    private final int[] rb;   // first operand, or branch condition
    private final int[] rc;   // second operand / immediate base / noise or spline index
    private final double[] imm;
    private final int[] kReg;
    private final double[] kVal;
    private final NpuNoise.NormalNoise[] noises;
    private final NpuDf.Spline[] splines;
    private final int regs;
    private final int out;

    private NpuDfProgram(Builder b) {
        this.op = Arrays.copyOf(b.op, b.n);
        this.ra = Arrays.copyOf(b.ra, b.n);
        this.rb = Arrays.copyOf(b.rb, b.n);
        this.rc = Arrays.copyOf(b.rc, b.n);
        this.imm = Arrays.copyOf(b.imm, b.immN);
        this.kReg = Arrays.copyOf(b.kReg, b.k);
        this.kVal = Arrays.copyOf(b.kVal, b.k);
        this.noises = b.noises.toArray(new NpuNoise.NormalNoise[0]);
        this.splines = b.splines.toArray(new NpuDf.Spline[0]);
        this.regs = b.regs;
        this.out = b.out;
    }

    public static NpuDfProgram build(NpuDf root) {
        Builder b = new Builder();
        b.out = root.emit(b);
        return new NpuDfProgram(b);
    }

    public int instructions() { return op.length; }

    /**
     * Positions of every OP_NOISE in the program, computed once.
     *
     * The assist path needs this because it cannot know from outside which noises the
     * program will ask for: OP_JZ means the set that actually executes can differ per
     * point. The answer is not to guess - it is to evaluate every noise the program
     * contains, for every lattice point, up front. Noise is pure, so unused values cost
     * work and change nothing.
     */
    private int[] noiseOpPcs;

    private int[] noiseOpPcs() {
        int[] a = noiseOpPcs;
        if (a == null) {
            int n = 0;
            for (int i = 0; i < op.length; i++) if (op[i] == OP_NOISE) n++;
            a = new int[n];
            int j = 0;
            for (int i = 0; i < op.length; i++) if (op[i] == OP_NOISE) a[j++] = i;
            noiseOpPcs = a;
        }
        return a;
    }

    public int noiseEvalCount() { return noiseOpPcs().length; }

    public int noiseIndexAt(int i) { return rb[noiseOpPcs()[i]]; }

    public double noiseXzScaleAt(int i) { return imm[rc[noiseOpPcs()[i]]]; }

    public double noiseYScaleAt(int i) { return imm[rc[noiseOpPcs()[i]] + 1]; }

    public NpuNoise.NormalNoise[] theNoises() { return noises; }

    public int registers() { return regs; }

    public int constants() { return kReg.length; }

    /** One reusable register file per thread; the caller keeps it across samples. */
    public double[] registersFor() { return new double[Math.max(regs, 8)]; }

    public double eval(double x, double y, double z, double[] r) {
        return eval(x, y, z, r, null);
    }

    /**
     * nv, when non-null, holds one precomputed value per noise channel, indexed the same
     * way as {@code noises}. The assist path fills it from a batch evaluation and passes
     * it here, so OP_NOISE reads a table instead of calling into the noise object.
     *
     * Values come from the same NormalNoise instances, so this is not an approximation -
     * it is the same numbers arriving by a different route.
     */
    public double eval(double x, double y, double z, double[] r, float[] nv) {
        for (int i = 0; i < kReg.length; i++) r[kReg[i]] = kVal[i];
        r[0] = x;
        r[1] = y;
        r[2] = z;

        final int[] op = this.op, ra = this.ra, rb = this.rb, rc = this.rc;
        final double[] imm = this.imm;
        final NpuNoise.NormalNoise[] nz = this.noises;
        final NpuDf.Spline[] sp = this.splines;

        int pc = 0;
        while (pc < op.length) {
            switch (op[pc]) {
                case OP_ADD: r[ra[pc]] = r[rb[pc]] + r[rc[pc]]; pc++; break;
                case OP_SUB: r[ra[pc]] = r[rb[pc]] - r[rc[pc]]; pc++; break;
                case OP_MUL: r[ra[pc]] = r[rb[pc]] * r[rc[pc]]; pc++; break;
                case OP_MIN: {
                    double p = r[rb[pc]], q = r[rc[pc]];
                    r[ra[pc]] = p < q ? p : q;
                    pc++; break;
                }
                case OP_MAX: {
                    double p = r[rb[pc]], q = r[rc[pc]];
                    r[ra[pc]] = p > q ? p : q;
                    pc++; break;
                }
                case OP_NEG: r[ra[pc]] = -r[rb[pc]]; pc++; break;
                case OP_ABS: r[ra[pc]] = Math.abs(r[rb[pc]]); pc++; break;
                case OP_SQUARE: {
                    double v = r[rb[pc]];
                    r[ra[pc]] = v * v;
                    pc++; break;
                }
                case OP_CUBE: {
                    double v = r[rb[pc]];
                    r[ra[pc]] = v * v * v;
                    pc++; break;
                }
                case OP_QUARTER_NEG: {
                    double v = r[rb[pc]];
                    r[ra[pc]] = v > 0 ? v : v * 0.25;
                    pc++; break;
                }
                case OP_HALF_NEG: {
                    double v = r[rb[pc]];
                    r[ra[pc]] = v > 0 ? v : v * 0.5;
                    pc++; break;
                }
                case OP_SQUEEZE: {
                    double v = r[rb[pc]];
                    double c = v < -1 ? -1 : (v > 1 ? 1 : v);
                    r[ra[pc]] = c / 2.0 - c * c * c / 24.0;
                    pc++; break;
                }
                case OP_CLAMP: {
                    int base = rc[pc];
                    double v = r[rb[pc]], lo = imm[base], hi = imm[base + 1];
                    r[ra[pc]] = v < lo ? lo : (v > hi ? hi : v);
                    pc++; break;
                }
                case OP_NOISE: {
                    int base = rc[pc];
                    if (nv != null) {
                        r[ra[pc]] = nv[rb[pc]];
                    } else {
                        r[ra[pc]] = nz[rb[pc]].getValue(r[0] * imm[base], r[1] * imm[base + 1], r[2] * imm[base]);
                    }
                    pc++; break;
                }
                case OP_SPLINE:
                    r[ra[pc]] = sp[rc[pc]].apply(r[rb[pc]]);
                    pc++; break;
                case OP_YGRAD: {
                    int base = rc[pc];
                    double t = (r[rb[pc]] - imm[base]) / imm[base + 1];
                    if (t < 0) t = 0; else if (t > 1) t = 1;
                    r[ra[pc]] = imm[base + 2] + t * imm[base + 3];
                    pc++; break;
                }
                case OP_LERP: {
                    int base = rc[pc];
                    double t = r[rb[pc]];
                    if (t < 0) t = 0; else if (t > 1) t = 1;
                    double f = r[(int) imm[base]], s = r[(int) imm[base + 1]];
                    r[ra[pc]] = f + t * (s - f);
                    pc++; break;
                }
                case OP_IN_RANGE: {
                    int base = rc[pc];
                    double v = r[rb[pc]];
                    r[ra[pc]] = (v >= imm[base] && v < imm[base + 1]) ? 1.0 : 0.0;
                    pc++; break;
                }
                case OP_COPY: r[ra[pc]] = r[rb[pc]]; pc++; break;
                case OP_JZ: pc = (r[rb[pc]] == 0.0) ? ra[pc] : pc + 1; break;
                case OP_JMP: pc = ra[pc]; break;
                default: pc++;
            }
        }
        return r[out];
    }

    // ---- compiler --------------------------------------------------------------

    /**
     * Builds the instruction stream. Each node emits itself and returns the register holding its
     * value, so a parent never needs to know what its child was.
     */
    public static final class Builder {

        int[] op = new int[256];
        int[] ra = new int[256];
        int[] rb = new int[256];
        int[] rc = new int[256];
        int n = 0;
        double[] imm = new double[256];
        int immN = 0;
        boolean[] isConst = new boolean[64];
        double[] cval = new double[64];
        int[] kReg = new int[64];
        double[] kVal = new double[64];
        int k = 0;
        int regs = 3;
        final List<NpuNoise.NormalNoise> noises = new ArrayList<>();
        final List<NpuDf.Spline> splines = new ArrayList<>();
        int out = -1;

        public int reg() {
            int r = regs++;
            if (r >= isConst.length) {
                int m = isConst.length;
                while (m <= r) m *= 2;
                isConst = Arrays.copyOf(isConst, m);
                cval = Arrays.copyOf(cval, m);
            }
            return r;
        }

        public int constant(double v) {
            int r = reg();
            isConst[r] = true;
            cval[r] = v;
            if (k == kReg.length) {
                kReg = Arrays.copyOf(kReg, k * 2);
                kVal = Arrays.copyOf(kVal, k * 2);
            }
            kReg[k] = r;
            kVal[k] = v;
            k++;
            return r;
        }

        private void emit(int o, int a, int b, int c) {
            if (n == op.length) {
                int m = n * 2;
                op = Arrays.copyOf(op, m);
                ra = Arrays.copyOf(ra, m);
                rb = Arrays.copyOf(rb, m);
                rc = Arrays.copyOf(rc, m);
            }
            op[n] = o;
            ra[n] = a;
            rb[n] = b;
            rc[n] = c;
            n++;
        }

        private int immd(double v) {
            if (immN == imm.length) imm = Arrays.copyOf(imm, immN * 2);
            imm[immN] = v;
            return immN++;
        }

        private int immd2(double v0, double v1) {
            int base = immN;
            immd(v0);
            immd(v1);
            return base;
        }

        public int unary(int opcode, int s) {
            if (isConst[s]) {
                Double f = fold1(opcode, cval[s]);
                if (f != null) return constant(f.doubleValue());
            }
            int d = reg();
            emit(opcode, d, s, 0);
            return d;
        }

        public int binary(int opcode, int s0, int s1) {
            if (isConst[s0] && isConst[s1]) {
                Double f = fold2(opcode, cval[s0], cval[s1]);
                if (f != null) return constant(f.doubleValue());
            }
            int d = reg();
            emit(opcode, d, s0, s1);
            return d;
        }

        public int clamp(int s, double lo, double hi) {
            if (isConst[s]) {
                double v = cval[s];
                return constant(v < lo ? lo : (v > hi ? hi : v));
            }
            int base = immd2(lo, hi);
            int d = reg();
            emit(OP_CLAMP, d, s, base);
            return d;
        }

        public int noise(NpuNoise.NormalNoise n, double xzScale, double yScale) {
            int idx = noises.size();
            noises.add(n);
            int base = immd2(xzScale, yScale);
            int d = reg();
            emit(OP_NOISE, d, idx, base);
            return d;
        }

        public int spline(NpuDf.Spline sp, int coord) {
            int idx = splines.size();
            splines.add(sp);
            int d = reg();
            emit(OP_SPLINE, d, coord, idx);
            return d;
        }

        /** yPlus(from, span, v0, dv): clamp((y - from) / span, 0, 1) * dv + v0. */
        public int yGrad(double from, double span, double v0, double dv) {
            int base = immN;
            immd(from);
            immd(span);
            immd(v0);
            immd(dv);
            int d = reg();
            emit(OP_YGRAD, d, 1, base);
            return d;
        }

        public int lerp(int alpha, int first, int second) {
            int base = immN;
            immd(first);
            immd(second);
            int d = reg();
            emit(OP_LERP, d, alpha, base);
            return d;
        }

        /**
         * range_choice compiles to a real branch, not to "compute both and select".
         *
         * Computing both would double the work of every subtree under the branch, and the whole
         * point of this class is to stop paying for work a sample does not need. The two arms are
         * emitted as straight-line code and the branch jumps over the arm it did not take.
         */
        public int rangeChoice(int input, double lo, double hi, NpuDf inRange, NpuDf outOfRange) {
            int dst = reg();
            int cond = reg();
            int base = immd2(lo, hi);
            emit(OP_IN_RANGE, cond, input, base);
            int jz = n;
            emit(OP_JZ, 0, cond, 0);
            int rIn = inRange.emit(this);
            emit(OP_COPY, dst, rIn, 0);
            int jmp = n;
            emit(OP_JMP, 0, 0, 0);
            ra[jz] = n;
            int rOut = outOfRange.emit(this);
            emit(OP_COPY, dst, rOut, 0);
            ra[jmp] = n;
            return dst;
        }

        private static Double fold1(int opcode, double v) {
            switch (opcode) {
                case OP_NEG: return Double.valueOf(-v);
                case OP_ABS: return Double.valueOf(Math.abs(v));
                case OP_SQUARE: return Double.valueOf(v * v);
                case OP_CUBE: return Double.valueOf(v * v * v);
                case OP_QUARTER_NEG: return Double.valueOf(v > 0 ? v : v * 0.25);
                case OP_HALF_NEG: return Double.valueOf(v > 0 ? v : v * 0.5);
                case OP_SQUEEZE: {
                    double c = v < -1 ? -1 : (v > 1 ? 1 : v);
                    return Double.valueOf(c / 2.0 - c * c * c / 24.0);
                }
                default: return null;
            }
        }

        private static Double fold2(int opcode, double a, double b) {
            switch (opcode) {
                case OP_ADD: return Double.valueOf(a + b);
                case OP_SUB: return Double.valueOf(a - b);
                case OP_MUL: return Double.valueOf(a * b);
                case OP_MIN: return Double.valueOf(a < b ? a : b);
                case OP_MAX: return Double.valueOf(a > b ? a : b);
                default: return null;
            }
        }
    }
}
