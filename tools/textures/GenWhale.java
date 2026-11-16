import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Generator für "Oruun, den Leviathan" und seinen Diener, den Sternling. Deterministisch, nur JDK.
 *
 * Einzige Quelle für Geometrie, UVs und Texturen. Schreibt:
 *  - textures/entity/void_boss.png, void_boss_glow.png          Haut + Leuchtschicht (Phase 1-2)
 *  - textures/entity/void_boss_p3.png, void_boss_p3_glow.png    Phase 3: Haut aufgerissen, Galaxie leuchtet durch
 *  - textures/entity/void_debtor.png, void_debtor_glow.png      Sternling
 *  - kotlin client/boss/WhaleModelData.kt, StarlingModelData.kt TexturedModelData (Teile, Quader, UVs)
 *
 * Aufruf (im Projektordner):  java tools/textures/GenWhale.java [projektRoot] [--preview <ordner>]
 *
 * Leviathan: 1 Einheit = 0,3 Blöcke (Renderer skaliert x4,8), 3 Texel je Einheit. +Y zeigt nach unten, der Wal
 * schaut nach -Z, seine linke Seite ist +X. Ursprung = Körpermitte. Umrechnung aus docs/SPEC_LEVIATHAN.md:
 * z = -f/0,3, x = -s/0,3, y = -u/0,3. Nur Harpunen haben eine Ruhedrehung.
 * Jedes Texel wird aus seiner 3D-Position gemalt, damit Nebel, Sterne, Narben und Furchen über Kanten hinweg laufen.
 * Die Vorschau zeichnet auf Wunsch die Hitbox-Segmente aus boss/LeviathanBody.kt als Drahtgitter darüber.
 */
public class GenWhale {
    enum F { TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK }

    static final class Part {
        final String name;
        final Part parent;
        final double ax, ay, az;
        double rx, ry, rz;
        final List<Cub> cubes = new ArrayList<>();
        final List<Part> kids = new ArrayList<>();
        double[] M;

        Part(String n, Part p, double x, double y, double z) {
            name = n; parent = p; ax = x; ay = y; az = z;
            if (p != null) p.kids.add(this);
        }
        /** Ruhedrehung um den Drehpunkt; Quader und Kinder werden in der ungedrehten Lage angegeben. */
        Part rot(double x, double y, double z) { rx = x; ry = y; rz = z; return this; }
        String path() { return parent == null ? name : parent.path() + "/" + name; }
    }

    static final class Cub {
        Part part;
        String mat, share;
        double x0, y0, z0;
        int w, h, d, u = -1, v;
        /** Verlauf entlang einer Achse (Kristalle/Zähne): 0 = Wurzel, 1 = Spitze. */
        int gax = -1;
        double g0, g1;
        int tag;
    }

    /** Ein Texel: Ruheposition, Normale, Lage im Quader (0..1) und die vier Ergebnisfarben (ARGB). */
    static final class Tex {
        Cub c; F f;
        double x, y, z, nx, ny, nz, fu, fv, fw;
        int px, py, s1, g1, s3, g3;
    }

    interface Painter { void paint(Tex t); }
    interface Visitor { void at(Tex t); }

    static final class Model {
        final String name;
        final int W, H, res;
        final List<Part> parts = new ArrayList<>();
        final List<Cub> cubs = new ArrayList<>();
        final Map<String, Part> byName = new LinkedHashMap<>();
        int[] s1, g1, s3, g3;

        Model(String name, int w, int h, int res) { this.name = name; W = w; H = h; this.res = res; }

        Part bone(String n, Part p, double x, double y, double z) {
            if (byName.containsKey(n)) throw new IllegalStateException("doppelt: " + n);
            Part q = new Part(n, p, x, y, z);
            parts.add(q); byName.put(n, q);
            return q;
        }

        /** Quader mit absoluten Ecken (Ruhehaltung). */
        Cub ab(Part p, String mat, double x0, double y0, double z0, double x1, double y1, double z1) {
            Cub c = new Cub();
            c.part = p; c.mat = mat; c.x0 = x0; c.y0 = y0; c.z0 = z0;
            c.w = (int) Math.round(x1 - x0); c.h = (int) Math.round(y1 - y0); c.d = (int) Math.round(z1 - z0);
            if (Math.abs(c.w - (x1 - x0)) > 1e-6 || Math.abs(c.h - (y1 - y0)) > 1e-6 || Math.abs(c.d - (z1 - z0)) > 1e-6 || c.w < 0 || c.h < 0 || c.d < 0)
                throw new IllegalStateException("Maße nicht ganzzahlig: " + p.name + " " + mat);
            p.cubes.add(c); cubs.add(c);
            return c;
        }

        /** Achteckiger Querschnitt aus zwei Quadern (breit+flach, schmal+hoch). */
        void oct(Part p, String mat, double z0, double z1, double hw, double top, double bot, double c) {
            ab(p, mat, -hw, top + c, z0, hw, bot - c, z1);
            ab(p, mat, -(hw - c), top, z0, hw - c, bot, z1);
        }

        /** Runder Querschnitt aus drei Quadern (Zwölfeck): [r] = Radius der Rundung. */
        void ring3(Part p, String mat, double z0, double z1, double hw, double top, double bot, int r) {
            int mi = Math.max(1, (int) Math.round(r * 0.36));
            ab(p, mat, -hw, top + r, z0, hw, bot - r, z1);
            ab(p, mat, -(hw - mi), top + mi, z0, hw - mi, bot - mi, z1);
            ab(p, mat, -(hw - r), top, z0, hw - r, bot, z1);
        }

        /** Wie [ring3], aber unten gerade abgeschnitten (Oberkopf bis zur Lippe). */
        void dome(Part p, String mat, double z0, double z1, double hw, double top, double lip, int r) {
            int mi = Math.max(1, (int) Math.round(r * 0.36));
            ab(p, mat, -hw, top + r, z0, hw, lip, z1);
            ab(p, mat, -(hw - mi), top + mi, z0, hw - mi, lip, z1);
            ab(p, mat, -(hw - r), top, z0, hw - r, lip, z1);
        }

        void pack() {
            Map<String, Cub> first = new LinkedHashMap<>();
            List<Cub> items = new ArrayList<>();
            for (Cub c : cubs) {
                if (c.share != null) {
                    Cub f = first.get(c.share);
                    if (f != null) { if (f.w != c.w || f.h != c.h || f.d != c.d) throw new IllegalStateException("share " + c.share); continue; }
                    first.put(c.share, c);
                }
                items.add(c);
            }
            items.sort((a, b) -> (b.d + b.h) - (a.d + a.h));
            int x = 0, y = 0, row = 0;
            for (Cub c : items) {
                int uw = Math.max(1, 2 * (c.d + c.w)), vh = Math.max(1, c.d + c.h);
                if (x + uw > W) { x = 0; y += row; row = 0; }
                c.u = x; c.v = y; x += uw; row = Math.max(row, vh);
            }
            int used = y + row;
            System.out.println(name + ": " + parts.size() + " Teile, " + cubs.size() + " Quader, " + items.size() + " UV-Blöcke, Höhe " + used + "/" + H);
            if (used > H) throw new IllegalStateException(name + ": Textur zu klein, braucht " + used);
            for (Cub c : cubs) if (c.u < 0) { Cub f = first.get(c.share); c.u = f.u; c.v = f.v; }
        }

        void each(Cub c, Tex t, Visitor fn) {
            int Wc = c.w * res, Hc = c.h * res, Dc = c.d * res, U = c.u * res, V = c.v * res;
            for (F f : F.values()) {
                int na, nb;
                switch (f) {
                    case TOP, BOTTOM -> { na = Wc; nb = Dc; }
                    case RIGHT, LEFT -> { na = Dc; nb = Hc; }
                    default -> { na = Wc; nb = Hc; }
                }
                for (int a = 0; a < na; a++) for (int b = 0; b < nb; b++) {
                    double fa = (a + 0.5) / res, fb = (b + 0.5) / res;
                    t.nx = t.ny = t.nz = 0;
                    switch (f) {
                        case TOP -> { t.x = c.x0 + fa; t.z = c.z0 + fb; t.y = c.y0; t.ny = -1; t.px = U + Dc + a; t.py = V + (Dc - 1 - b); }
                        case BOTTOM -> { t.x = c.x0 + fa; t.z = c.z0 + fb; t.y = c.y0 + c.h; t.ny = 1; t.px = U + Dc + Wc + a; t.py = V + (Dc - 1 - b); }
                        case RIGHT -> { t.z = c.z0 + fa; t.y = c.y0 + fb; t.x = c.x0; t.nx = -1; t.px = U + (Dc - 1 - a); t.py = V + Dc + b; }
                        case LEFT -> { t.z = c.z0 + fa; t.y = c.y0 + fb; t.x = c.x0 + c.w; t.nx = 1; t.px = U + Dc + Wc + a; t.py = V + Dc + b; }
                        case FRONT -> { t.x = c.x0 + fa; t.y = c.y0 + fb; t.z = c.z0; t.nz = -1; t.px = U + Dc + a; t.py = V + Dc + b; }
                        default -> { t.x = c.x0 + fa; t.y = c.y0 + fb; t.z = c.z0 + c.d; t.nz = 1; t.px = U + 2 * Dc + Wc + (Wc - 1 - a); t.py = V + Dc + b; }
                    }
                    t.c = c; t.f = f;
                    t.fu = c.w > 0 ? (t.x - c.x0) / c.w : 0.5;
                    t.fv = c.h > 0 ? (t.y - c.y0) / c.h : 0.5;
                    t.fw = c.d > 0 ? (t.z - c.z0) / c.d : 0.5;
                    fn.at(t);
                }
            }
        }

        void paint(Painter p) {
            int n = W * res * H * res;
            s1 = new int[n]; g1 = new int[n]; s3 = new int[n]; g3 = new int[n];
            Set<String> done = new HashSet<>();
            Tex t = new Tex();
            for (Cub c : cubs) {
                if (c.share != null && !done.add(c.share)) continue;
                each(c, t, q -> {
                    q.s1 = q.g1 = q.s3 = q.g3 = 0;
                    p.paint(q);
                    int i = q.py * W * res + q.px;
                    s1[i] = q.s1; g1[i] = q.g1; s3[i] = q.s3; g3[i] = q.g3;
                });
            }
        }

        void png(int[] px, File f) throws Exception {
            BufferedImage img = new BufferedImage(W * res, H * res, BufferedImage.TYPE_INT_ARGB);
            // etwas gröbere Farbstufen: unsichtbar, aber die Datei wird deutlich kleiner
            int[] q = new int[px.length];
            for (int i = 0; i < px.length; i++) q[i] = (px[i] >>> 24) == 0 ? 0 : (px[i] & 0xfffcfcfc);
            img.setRGB(0, 0, W * res, H * res, q, 0, W * res);
            f.getParentFile().mkdirs();
            ImageIO.write(img, "png", f);
        }

        String kotlin(String obj, String doc) {
            StringBuilder sb = new StringBuilder();
            sb.append("// GENERIERT von tools/textures/GenWhale.java - nicht von Hand ändern\n");
            sb.append("package dev.henny.hha_void.client.boss\n\n");
            sb.append("import net.minecraft.client.model.Dilation\nimport net.minecraft.client.model.ModelData\nimport net.minecraft.client.model.ModelPart\n");
            sb.append("import net.minecraft.client.model.ModelPartBuilder\nimport net.minecraft.client.model.ModelTransform\nimport net.minecraft.client.model.TexturedModelData\n\n");
            sb.append("/**\n").append(doc).append(" */\nobject ").append(obj).append(" {\n");
            sb.append("    private const val S = ").append(fmt(1.0 / res)).append("\n\n");
            sb.append("    fun create(): TexturedModelData {\n        val data = ModelData()\n        val root = data.root\n");
            for (Part p : parts) {
                sb.append("        val p_").append(p.name).append(" = ").append(p.parent == null ? "root" : "p_" + p.parent.name)
                    .append(".addChild(\n            \"").append(p.name).append("\",\n            ModelPartBuilder.create()");
                for (Cub c : p.cubes) {
                    sb.append("\n                .uv(").append(c.u).append(", ").append(c.v).append(").cuboid(")
                        .append(fmt(c.x0 - p.ax)).append(", ").append(fmt(c.y0 - p.ay)).append(", ").append(fmt(c.z0 - p.az)).append(", ")
                        .append(c.w).append("f, ").append(c.h).append("f, ").append(c.d).append("f, Dilation.NONE, S, S)");
                }
                double ox = p.ax - (p.parent == null ? 0 : p.parent.ax), oy = p.ay - (p.parent == null ? 0 : p.parent.ay), oz = p.az - (p.parent == null ? 0 : p.parent.az);
                sb.append(",\n            ModelTransform.of(").append(fmt(ox)).append(", ").append(fmt(oy)).append(", ").append(fmt(oz)).append(", ")
                    .append(fmt(p.rx)).append(", ").append(fmt(p.ry)).append(", ").append(fmt(p.rz)).append("),\n        )\n");
            }
            sb.append("        return TexturedModelData.of(data, ").append(W * res).append(", ").append(H * res).append(")\n    }\n\n");
            sb.append("    /** Weg jedes Teils von der Wurzel aus. */\n    private val PATHS = mapOf(\n");
            for (Part p : parts) sb.append("        \"").append(p.name).append("\" to \"").append(p.path()).append("\",\n");
            sb.append("    )\n\n    fun part(root: ModelPart, name: String): ModelPart =\n");
            sb.append("        PATHS.getValue(name).split('/').fold(root) { p, n -> p.getChild(n) }\n}\n");
            return sb.toString();
        }

        // ---------- Vorschau (Software-Rasterer, nur zum schnellen Ansehen der Texturen) ----------
        /** Pose je Teil: {rx, ry, rz} zusätzlich zur Ruhedrehung, optional {sx, sy, sz} und {tx, ty, tz}. */
        void computeM(Map<String, double[]> pose) {
            for (Part p : parts) {
                double[] pm = p.parent == null ? ident() : p.parent.M;
                double ox = p.ax - (p.parent == null ? 0 : p.parent.ax), oy = p.ay - (p.parent == null ? 0 : p.parent.ay), oz = p.az - (p.parent == null ? 0 : p.parent.az);
                double[] r = pose == null ? null : pose.get(p.name);
                if (r != null && r.length >= 9) { ox += r[6]; oy += r[7]; oz += r[8]; }
                double[] m = mul(pm, trans(ox, oy, oz));
                double a = p.rx + (r != null ? r[0] : 0), b = p.ry + (r != null ? r[1] : 0), c = p.rz + (r != null ? r[2] : 0);
                if (a != 0 || b != 0 || c != 0) m = mul(m, mul(rz(c), mul(ry(b), rx(a))));
                if (r != null && r.length >= 6) m = mul(m, new double[]{r[3], 0, 0, 0, 0, r[4], 0, 0, 0, 0, r[5], 0, 0, 0, 0, 1});
                p.M = m;
            }
        }

        void preview(File out, Map<String, double[]> pose, double yawDeg, double pitchDeg, double scale, int IW, int IH, boolean p3, boolean boxes) throws Exception {
            computeM(pose);
            double[] view = mul(rx(Math.toRadians(pitchDeg)), ry(Math.toRadians(yawDeg)));
            int[] img = new int[IW * IH];
            double[] zb = new double[IW * IH];
            java.util.Arrays.fill(img, 0xff05030c);
            java.util.Arrays.fill(zb, 1e9);
            int[] skin = p3 ? s3 : s1, glow = p3 ? g3 : g1;
            int TW = W * res;
            int sp = (int) Math.ceil(scale / res) + 1;
            Tex t = new Tex();
            for (int pass = 0; pass < 2; pass++) {
                final int ps = pass;
                for (Cub c : cubs) {
                    double[] m = mul(view, c.part.M);
                    each(c, t, q -> {
                        int si = skin[q.py * TW + q.px], gi = glow[q.py * TW + q.px];
                        boolean solid = (si >>> 24) >= 26;
                        if (ps == 0 && !solid) return;
                        if (ps == 1 && (solid || (gi >>> 24) == 0)) return;
                        double lx = q.x - c.part.ax, ly = q.y - c.part.ay, lz = q.z - c.part.az;
                        double X = m[0] * lx + m[1] * ly + m[2] * lz + m[3], Y = m[4] * lx + m[5] * ly + m[6] * lz + m[7], Z = m[8] * lx + m[9] * ly + m[10] * lz + m[11];
                        double nZ = m[8] * q.nx + m[9] * q.ny + m[10] * q.nz;
                        if (solid && nZ > 0.05) return;
                        double[] pm = c.part.M;
                        double wy = pm[4] * q.nx + pm[5] * q.ny + pm[6] * q.nz, wx = pm[0] * q.nx + pm[1] * q.ny + pm[2] * q.nz;
                        double shade = 0.78 - 0.22 * wy - 0.12 * Math.abs(wx);
                        int col = solid ? scale(si, shade) : 0;
                        int sx = (int) Math.floor(IW / 2.0 + X * scale - sp / 2.0), sy = (int) Math.floor(IH / 2.0 + Y * scale - sp / 2.0);
                        for (int dy = 0; dy < sp; dy++) for (int dx = 0; dx < sp; dx++) {
                            int x = sx + dx, y = sy + dy;
                            if (x < 0 || y < 0 || x >= IW || y >= IH) continue;
                            int i = y * IW + x;
                            if (ps == 0) {
                                if (Z >= zb[i]) continue;
                                zb[i] = Z;
                                img[i] = 0xff000000 | (mixc(col, gi, (gi >>> 24) / 255.0) & 0xffffff);
                            } else if (Z < zb[i] + 0.01) {
                                img[i] = 0xff000000 | (mixc(img[i], gi, (gi >>> 24) / 255.0 * 0.5) & 0xffffff);
                            }
                        }
                    });
                }
            }
            if (boxes) {
                // Hitbox-Segmente (LeviathanBody.SEGMENTS, im Flug) als Drahtgitter
                for (double[] s : SEGS) {
                    double cx = -s[1] * UNITS, cy = -s[2] * UNITS, cz = -s[0] * UNITS, hw = s[3] / 2 * UNITS, hh = s[4] / 2 * UNITS;
                    int col = s.length > 5 ? 0xffff5050 : 0xff60ff60;
                    for (int e = 0; e < 12; e++) {
                        int[][] ed = {{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
                        double[] a = corner(cx, cy, cz, hw, hh, ed[e][0]), b = corner(cx, cy, cz, hw, hh, ed[e][1]);
                        line(img, IW, IH, IW / 2.0 + (view[0] * a[0] + view[1] * a[1] + view[2] * a[2]) * scale, IH / 2.0 + (view[4] * a[0] + view[5] * a[1] + view[6] * a[2]) * scale,
                            IW / 2.0 + (view[0] * b[0] + view[1] * b[1] + view[2] * b[2]) * scale, IH / 2.0 + (view[4] * b[0] + view[5] * b[1] + view[6] * b[2]) * scale, col);
                    }
                }
            }
            BufferedImage bi = new BufferedImage(IW, IH, BufferedImage.TYPE_INT_ARGB);
            bi.setRGB(0, 0, IW, IH, img, 0, IW);
            out.getParentFile().mkdirs();
            ImageIO.write(bi, "png", out);
        }
    }

    static double[] corner(double cx, double cy, double cz, double hw, double hh, int i) {
        return new double[]{cx + ((i & 1) == 0 ? -hw : hw), cy + ((i & 2) == 0 ? -hh : hh), cz + ((i & 4) == 0 ? -hw : hw)};
    }

    static void line(int[] img, int W, int H, double x0, double y0, double x1, double y1, int col) {
        int n = (int) Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) + 1;
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * i / n), y = (int) Math.round(y0 + (y1 - y0) * i / n);
            if (x >= 0 && y >= 0 && x < W && y < H) img[y * W + x] = col;
        }
    }

    /** Einheiten je Block. */
    static final double UNITS = 1 / 0.3;

    /** Kopie von LeviathanBody.SEGMENTS (f, seitlich, oben, Breite, Höhe) – nur für die Vorschau. */
    static final double[][] SEGS = {
        {21.5, 0, -1.0, 9, 9}, {15, 0, 0.3, 13, 14}, {10.5, 0, 7.8, 3.6, 1.6, 1}, {7, 0, 0, 15, 16}, {3, 0, -7.8, 5, 3, 1},
        {-1, 0, 0, 15, 15.5}, {-8, 0, 0.3, 13, 13}, {-14, 0, 0.6, 10, 10}, {-19, 0, 0.8, 7, 7}, {-23, 0, 0.8, 5, 5},
        {-26.5, 0, 0.8, 13, 2.4}, {4, -11, -4.5, 7, 2.2}, {4, 11, -4.5, 7, 2.2}};

    // =====================================================================================
    // Mathe
    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            double s = 0;
            for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
            r[i * 4 + j] = s;
        }
        return r;
    }
    static double[] ident() { return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }
    static double[] trans(double x, double y, double z) { return new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1}; }
    static double[] rx(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1}; }
    static double[] ry(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1}; }
    static double[] rz(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }
    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }
    static double ramp(double x, double a, double b) { double k = clamp((x - a) / (b - a), 0, 1); return k * k * (3 - 2 * k); }
    static String fmt(double v) {
        if (Math.abs(v) < 1e-9) v = 0;
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s + "f";
    }

    static double hash(int a, int b, int c, int s) {
        int h = a * 374761393 + b * 668265263 + c * 1440662683 + s * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 15;
        return (h & 0xffffff) / (double) 0x1000000;
    }
    static double vnoise(double x, double y, double z, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        double r = 0;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
            r += hash(xi + dx, yi + dy, zi + dz, seed) * (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
        }
        return r;
    }
    static double fbm(double x, double y, double z, int seed) {
        double s = 0, a = 0.5, n = 0;
        for (int o = 0; o < 4; o++) { s += a * vnoise(x, y, z, seed + o * 17); n += a; x *= 2.03; y *= 2.03; z *= 2.03; a *= 0.5; }
        return s / n;
    }
    /** Zufallswert der nächstgelegenen Zelle des letzten [voronoi]-Aufrufs. */
    static double VCELL;
    /** Abstand zur nächsten und zweitnächsten Zelle. */
    static double[] voronoi(double x, double y, double z, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double f1 = 9, f2 = 9;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int cx = xi + dx, cy = yi + dy, cz = zi + dz;
            double px = cx + hash(cx, cy, cz, seed), py = cy + hash(cx, cy, cz, seed + 1), pz = cz + hash(cx, cy, cz, seed + 2);
            double d = Math.sqrt((px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z));
            if (d < f1) { f2 = f1; f1 = d; VCELL = hash(cx, cy, cz, seed + 3); } else if (d < f2) f2 = d;
        }
        return new double[]{f1, f2};
    }

    static int argb(int rgb, double a) { return ((int) Math.round(clamp(a, 0, 255)) << 24) | (rgb & 0xffffff); }
    static int scale(int c, double k) {
        int r = (int) clamp(((c >> 16) & 255) * k, 0, 255), g = (int) clamp(((c >> 8) & 255) * k, 0, 255), b = (int) clamp((c & 255) * k, 0, 255);
        return (c & 0xff000000) | (r << 16) | (g << 8) | b;
    }
    static int mixc(int a, int b, double t) {
        t = clamp(t, 0, 1);
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
    static int grad(double t, double[] ts, int[] cs) {
        if (t <= ts[0]) return cs[0];
        for (int i = 1; i < ts.length; i++) if (t <= ts[i]) return mixc(cs[i - 1], cs[i], (t - ts[i - 1]) / (ts[i] - ts[i - 1]));
        return cs[cs.length - 1];
    }
    static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double l = dx * dx + dy * dy;
        double k = l < 1e-9 ? 0 : clamp(((px - ax) * dx + (py - ay) * dy) / l, 0, 1);
        return Math.hypot(px - ax - dx * k, py - ay - dy * k);
    }

    // =====================================================================================
    // Leviathan: Geometrie
    static final double HEART_X = 0, HEART_Y = 31, HEART_Z = -10;
    static final double EYE_Y = -7, EYE_Z = -58;
    /** Einstichstellen der Harpunen (für die Kruste drumherum). */
    static final List<double[]> ENTRIES = new ArrayList<>();

    static Model whale() {
        Model m = new Model("whale", 680, 544, 3);
        Part root = m.bone("root", null, 0, 0, 0);
        Part body = m.bone("body", root, 0, 0, 0);
        Part chest = m.bone("chest", body, 0, 0, 0);
        Part chestSkin = m.bone("chest_skin", chest, 0, 0, -4);
        m.ring3(chestSkin, "skin", -22, 14, 26, -27, 27, 11);

        // ---- Hals mit Blasloch und Kehlfalten
        Part neck = m.bone("neck", chest, 0, 0, -20);
        Part neckSkin = m.bone("neck_skin", neck, 0, 0, -32);
        m.ring3(neckSkin, "skin", -46, -18, 25, -26, 26, 10);
        m.ab(neckSkin, "core", -11, 26, -45, 11, 27, -19);
        for (int i = 0; i < 6; i++) {
            double x = -10 + 4 * i;
            Part s = m.bone("pn" + i, neckSkin, x, 26, -32);
            m.ab(s, "slat", x - 2, 26, -46, x + 2, 28, -18);
        }
        // Blasloch: zwei Hautlappen über einem glühenden Schlot, Kraterrand
        m.ab(neckSkin, "blowglow", -4, -27, -38, 4, -26, -32);
        m.ab(neckSkin, "hump", -7, -27, -41, 7, -26, -39);
        m.ab(neckSkin, "hump", -7, -27, -31, 7, -26, -29);
        m.ab(neckSkin, "hump", 5, -27, -39, 7, -26, -31);
        m.ab(neckSkin, "hump", -7, -27, -39, -5, -26, -31);
        for (int s = -1; s <= 1; s += 2) {
            Part b = m.bone(s > 0 ? "blow_l" : "blow_r", neckSkin, s * 5, -26, -35);
            mir(m, b, "blow", s, 0, -28, -39, 5, -26, -31);
        }
        Part tglow = m.bone("throat_glow", neck, 0, 15, -47);
        m.ab(tglow, "tglow", -12, 9, -47, 12, 21, -46);

        // ---- Kopf: wuchtiger, stumpfer Pottwal-Schädel; Lippe bei y = 5
        Part head = m.bone("head", neck, 0, -2, -44);
        m.dome(head, "head", -62, -42, 24, -25, 5, 11);
        m.dome(head, "head", -74, -62, 23, -23, 5, 10);
        m.dome(head, "head", -84, -74, 21, -20, 5, 9);
        m.dome(head, "head", -88, -84, 18, -15, 4, 6);
        for (int s = -1; s <= 1; s += 2) {
            // Auge: klein, aber hell; Brauenwulst, Unterlid, Lichthof
            mir(m, head, "eye", s, 24, EYE_Y - 2, EYE_Z - 3, 25, EYE_Y + 2, EYE_Z + 2);
            mir(m, head, "brow", s, 24, EYE_Y - 4, EYE_Z - 5, 26, EYE_Y - 2, EYE_Z + 4);
            mir(m, head, "brow", s, 24, EYE_Y + 2, EYE_Z - 4, 25, EYE_Y + 3, EYE_Z + 3);
            Part halo = m.bone(s > 0 ? "halo_l" : "halo_r", head, s * 26, EYE_Y, EYE_Z - 0.5);
            mir(m, halo, "halo", s, 26, EYE_Y - 8, EYE_Z - 8.5, 26, EYE_Y + 8, EYE_Z + 7.5);
        }
        // obere Zähne: kristallin, hängen außen über die Unterlippe
        int n = 0;
        double[][] upper = {{22.5, -45.5}, {22.5, -50.5}, {22.5, -55.5}, {22.5, -60}, {21.5, -65}, {21.5, -69.5}, {19.5, -76.5}, {19.5, -81}};
        for (double[] u : upper) for (int s = -1; s <= 1; s += 2) {
            int len = 5 + (int) (hash(n, 3, 7, 11) * 4);
            shard(m, head, "tooth", s * u[0], 4, u[1], 1, 1, 3, len, "tu");
            n++;
        }
        for (double x : new double[]{-8.5, -3, 3, 8.5}) shard(m, head, "tooth", x, 3, -86.5, 1, 1, 3, 5 + (int) (hash(n++, 3, 7, 11) * 2), "tu");

        // ---- Unterkiefer: länger als der Oberkiefer, Wanne aus Boden und Wänden, vorn die großen Hauer
        Part jaw = m.bone("jaw", head, 0, 8, -40);
        m.ab(jaw, "jaw", -20, 20, -62, 20, 25, -40);
        m.ab(jaw, "jaw", -18, 19, -78, 18, 24, -62);
        m.ab(jaw, "jaw", -15, 18, -88, 15, 22, -78);
        m.ab(jaw, "jaw", -12, 17, -95, 12, 20, -88);
        for (int s = -1; s <= 1; s += 2) {
            mir(m, jaw, "jaw", s, 16, 7, -62, 20, 20, -40);
            mir(m, jaw, "jaw", s, 14, 7, -78, 18, 19, -62);
            mir(m, jaw, "jaw", s, 11, 7, -88, 15, 18, -78);
            mir(m, jaw, "jaw", s, 8, 8, -93, 12, 17, -88);
        }
        m.ab(jaw, "jaw", -12, 8, -95, 12, 17, -93);
        m.ab(jaw, "tongue", -8, 17, -62, 8, 20, -47);
        m.ab(jaw, "tongue", -7, 16, -85, 7, 19, -62);
        double[][] lower = {{18, -44}, {18, -49}, {18, -54}, {18, -59}, {16, -64.5}, {16, -69}, {16, -73.5}, {13, -80}, {13, -84.5}};
        for (double[] u : lower) for (int s = -1; s <= 1; s += 2) {
            int len = 5 + (int) (hash(n, 3, 7, 11) * 4);
            shard(m, jaw, "tooth", s * u[0], 8, u[1], 1, -1, 3, len, "tl");
            n++;
        }
        for (int s = -1; s <= 1; s += 2) {
            shard(m, jaw, "tooth", s * 10, 9, -90.5, 1, -1, 3, 9, "tl");
            shard(m, jaw, "tooth", s * 9.5, 9, -94, 1, -1, 3, 10, "tl");
            shard(m, jaw, "tooth", s * 4, 9, -94, 1, -1, 4, 13, "tl");
        }
        // Kehlsack: dehnt sich beim Einsaugen; darunter Lamellen, die auseinandergehen und die Glut freigeben
        Part throat = m.bone("throat", jaw, 0, 24, -60);
        m.ab(throat, "sac", -14, 24, -78, 14, 27, -44);
        m.ab(throat, "sac", -10, 22, -88, 10, 25, -78);
        m.ab(throat, "core", -11, 27, -77, 11, 28, -45);
        for (int i = 0; i < 6; i++) {
            double x = -10 + 4 * i;
            Part s = m.bone("pj" + i, throat, x, 27, -61);
            m.ab(s, "slat", x - 2, 27, -78, x + 2, 29, -44);
        }
        // Wangenhaut im Maulwinkel: dreht halb mit dem Kiefer mit
        for (int s = -1; s <= 1; s += 2) {
            Part ch = m.bone(s > 0 ? "cheek_l" : "cheek_r", head, s * 21.5, 8, -40);
            mir(m, ch, "cheek", s, 21, 5, -54, 22, 17, -40);
        }

        // ---- Hinterleib: sechs Wirbelgelenke
        Part belly = m.bone("belly", chest, 0, 0, 12);
        Part bellySkin = m.bone("belly_skin", belly, 0, 0, 20);
        m.ring3(bellySkin, "skin", 10, 20, 25, -26, 26, 10);
        m.ring3(bellySkin, "skin", 20, 30, 23, -24, 23, 9);
        Part hip = m.bone("hip", belly, 0, -1, 30);
        m.ring3(hip, "skin", 28, 38, 21, -22, 20, 8);
        m.ring3(hip, "skin", 38, 46, 19, -20, 17, 7);
        // Rückenhöcker (flach, begehbar) und Knöchel entlang des Schwanzstiels
        m.ab(hip, "hump", -4, -25, 29, 4, -20, 43);
        m.ab(hip, "hump", -2, -27, 32, 2, -25, 40);
        Part tail1 = m.bone("tail1", hip, 0, -2, 46);
        m.ring3(tail1, "skin", 44, 53, 17, -19, 15, 6);
        m.ring3(tail1, "skin", 53, 60, 14, -17, 12, 5);
        m.ab(tail1, "hump", -2, -21, 46, 2, -19, 51);
        m.ab(tail1, "hump", -2, -19, 54, 2, -17, 58);
        Part tail2 = m.bone("tail2", tail1, 0, -3, 60);
        m.oct(tail2, "skin", 58, 66, 12, -15, 9, 4);
        m.oct(tail2, "skin", 66, 72, 10, -13, 7, 3);
        m.ab(tail2, "hump", -1.5, -17, 61, 1.5, -15, 65);
        Part tail3 = m.bone("tail3", tail2, 0, -3, 72);
        m.oct(tail3, "skin", 70, 78, 8, -11, 5, 3);
        m.oct(tail3, "skin", 78, 86, 6, -9, 3, 2);
        Part fluke = m.bone("fluke", tail3, 0, -3, 84);
        m.ab(fluke, "fluke", -7, -7, 82, 7, 1, 97);
        for (int s = -1; s <= 1; s += 2) {
            Part lobe = m.bone(s > 0 ? "fluke_l" : "fluke_r", fluke, s * 5, -3, 86);
            mir(m, lobe, "fluke", s, 5, -6, 84, 14, -1, 101);
            // rechts fehlt ein Stück der Hinterkante (alter Biss)
            if (s > 0) mir(m, lobe, "fluke", s, 14, -5.5, 86, 22, -1.5, 104);
            else { mir(m, lobe, "fluke", s, 14, -5.5, 86, 18, -1.5, 103); mir(m, lobe, "fluke", s, 18, -5.5, 86, 22, -1.5, 96); }
            Part tip = m.bone(s > 0 ? "fluke_l2" : "fluke_r2", lobe, s * 22, -3.5, 94);
            mir(m, tip, "fluke", s, 22, -5, 90, 29, -2, 106);
            mir(m, tip, "fluke", s, 29, -4.5, 95, 34, -2.5, 108);
            mir(m, tip, "fluke", s, 34, -4, 100, 37, -3, 108);
        }

        // ---- Brustflossen: drei Glieder
        for (int s = -1; s <= 1; s += 2) {
            Part fin = m.bone(s > 0 ? "fin_l" : "fin_r", chest, s * 25, 13, -12);
            mir(m, fin, "fin", s, 23, 11, -22, 41, 15, -2);
            Part fin2 = m.bone(s > 0 ? "fin_l2" : "fin_r2", fin, s * 41, 13, -12);
            mir(m, fin2, "fin", s, 39, 11.5, -20, 54, 14.5, -5);
            Part fin3 = m.bone(s > 0 ? "fin_l3" : "fin_r3", fin2, s * 54, 13, -12);
            mir(m, fin3, "fin", s, 52, 12, -17, 63, 14, -7);
            mir(m, fin3, "fin", s, 61, 12.5, -14, 67, 13.5, -8);
            // Sternenkruste an der Vorderkante
            shard(m, fin, "crystal", s * 29, 13, -22, 2, -1, 2, 3, "cf");
            shard(m, fin, "crystal", s * (s > 0 ? 36 : 34), 13, -22, 2, -1, 3, 4, "cf");
            shard(m, fin2, "crystal", s * 46, 13, -20, 2, -1, 2, 3, "cf");
            if (s < 0) shard(m, fin2, "crystal", s * 51, 13, -20, 2, -1, 2, 2, "cf");
        }

        // ---- Sternenherz in einer Kristallfassung
        Part heart = m.bone("heart", chest, HEART_X, 27, HEART_Z);
        m.ab(heart, "heart", -5, 24, HEART_Z - 5, 5, 28, HEART_Z + 5);
        m.ab(heart, "heart", -7, 28, HEART_Z - 7, 7, 32, HEART_Z + 7);
        m.ab(heart, "heart", -5, 32, HEART_Z - 5, 5, 35, HEART_Z + 5);
        m.ab(heart, "heart", -3, 35, HEART_Z - 3, 3, 37, HEART_Z + 3);
        m.ab(heart, "heart", -1, 37, HEART_Z - 1, 1, 39, HEART_Z + 1);
        m.ab(heart, "heartray", -19, 27, HEART_Z - 1, 19, 28, HEART_Z + 1);
        m.ab(heart, "heartray", -1, 27, HEART_Z - 17, 1, 28, HEART_Z + 17);
        for (int s = -1; s <= 1; s += 2) {
            shard(m, heart, "crystal", s * 10, 26, HEART_Z, 1, 1, 3, 7, "hc");
            shard(m, heart, "crystal", 0, 26, HEART_Z + s * 10, 1, 1, 3, 6, "hc");
        }
        Part flare = m.bone("heart_flare", heart, HEART_X, HEART_Y, HEART_Z);
        m.ab(flare, "flare", -17, HEART_Y, HEART_Z - 17, 17, HEART_Y, HEART_Z + 17);
        m.ab(flare, "flare", -17, HEART_Y - 17, HEART_Z, 17, HEART_Y + 17, HEART_Z);
        m.ab(flare, "flare", 0, HEART_Y - 17, HEART_Z - 17, 0, HEART_Y + 17, HEART_Z + 17);

        // ---- Sternenkruste (Seepocken aus Kristall): Stirn, Schnauze, Lippen, Kinn, Rücken
        double[][] crust = {
            // x, y, z, Achse, Richtung, Breite, Länge
            {-9, -23, -70, 1, -1, 3, 5}, {-5, -23, -66, 1, -1, 2, 3}, {11, -20, -80, 1, -1, 3, 4}, {7, -20, -77, 1, -1, 2, 3},
            {-11, -20, -82, 1, -1, 2, 4}, {3, -15, -86, 1, -1, 2, 3}, {-6, -25, -50, 1, -1, 2, 3}, {12, -25, -46, 1, -1, 3, 5},
            {10, -25, -56, 1, -1, 2, 3}, {-12, -25, -58, 1, -1, 2, 2},
            {-10, -4, -88, 2, -1, 3, 4}, {12, -1, -88, 2, -1, 2, 3}, {-14, 1, -88, 2, -1, 2, 3}, {5, -6, -88, 2, -1, 2, 2}, {0, -11, -88, 2, -1, 3, 3},
            {24, 0, -48, 0, 1, 2, 3}, {24, -3, -53, 0, 1, 2, 2}, {-24, 1, -46, 0, -1, 3, 4}, {-23, 2, -70, 0, -1, 2, 3}, {23, -2, -66, 0, 1, 2, 2},
            {-21, -3, -79, 0, -1, 2, 2}};
        for (double[] c : crust) shard(m, head, "crystal", c[0], c[1], c[2], (int) c[3], (int) c[4], (int) c[5], (int) c[6], "ch");
        shard(m, jaw, "crystal", -6, 13, -95, 2, -1, 2, 3, "cj");
        shard(m, jaw, "crystal", 7, 12, -95, 2, -1, 3, 4, "cj");
        shard(m, jaw, "crystal", 0, 20, -91, 1, 1, 2, 3, "cj");
        shard(m, jaw, "crystal", -8, 20, -90, 1, 1, 3, 4, "cj");
        // Kristall-„Griffe" auf dem Rücken
        shard(m, chestSkin, "crystal", -8, -27, -4, 1, -1, 3, 6, "cb");
        shard(m, chestSkin, "crystal", 10, -27, 6, 1, -1, 2, 5, "cb");
        shard(m, chestSkin, "crystal", -12, -27, 9, 1, -1, 2, 3, "cb");
        shard(m, neckSkin, "crystal", 11, -26, -26, 1, -1, 2, 4, "cb");
        shard(m, bellySkin, "crystal", 6, -26, 15, 1, -1, 3, 5, "cb");
        shard(m, bellySkin, "crystal", -9, -24, 25, 1, -1, 2, 4, "cb");
        shard(m, hip, "crystal", 0, -27, 36, 1, -1, 2, 4, "cb");
        shard(m, hip, "crystal", -7, -22, 33, 1, -1, 2, 3, "cb");
        shard(m, tail1, "crystal", 5, -19, 49, 1, -1, 2, 3, "cb");

        // ---- Harpunen: Schaft, Tülle, zerrissenes Seil
        harpoon(m, hip, "harp1", 6, -22, 35, 26, -0.5, 0.22, 3, true);
        harpoon(m, chestSkin, "harp2", 26, -6, 4, 21, -0.35, 1.05, 3, false);
        harpoon(m, head, "harp3", 9, -25, -52, 9, 0.3, 0.4, 0, false);
        harpoon(m, chestSkin, "harp4", -17, -23, -9, 23, -0.4, -0.5, 3, true);
        harpoon(m, tail1, "harp5", -16, -6, 50, 13, -0.3, -1.25, 2, false);

        // ---- Ausbruch: Flanke rechts wölbt sich vor (bulge), reißt auf (wound: Glut, Hautlappen, Rippen, Lichtbahnen)
        Part bulge = m.bone("bulge", chestSkin, -22, 0, 0);
        m.ab(bulge, "bulge", -30, -13, -15, -22, 13, 15);
        m.ab(bulge, "bulge", -33, -9, -11, -30, 9, 11);
        m.ab(bulge, "bulge", -35, -5, -6, -33, 5, 6);
        Part wound = m.bone("wound", chestSkin, -26, 0, 0);
        m.ab(wound, "woundglow", -27, -12, -14, -27, 12, 14);
        m.ab(wound, "bone", -32, -6, -8, -26, -4, -6);
        m.ab(wound, "bone", -34, -8, -8, -32, -5, -6);
        m.ab(wound, "bone", -31, 2, -1, -26, 4, 1);
        m.ab(wound, "bone", -33, -1, 6, -26, 1, 8);
        m.ab(wound, "bone", -35, -3, 6, -33, 0, 8);
        Part ft = m.bone("flap_t", wound, -27, -11, 0);
        m.ab(ft, "flap", -28, -11, -10, -27, -1, 8);
        Part fb = m.bone("flap_b", wound, -27, 11, 0);
        m.ab(fb, "flap", -28, 1, -8, -27, 11, 10);
        Part ff = m.bone("flap_f", wound, -27, 0, -13);
        m.ab(ff, "flap", -28, -7, -13, -27, 6, -4);
        Part fk = m.bone("flap_k", wound, -27, 0, 13);
        m.ab(fk, "flap", -28, -6, 4, -27, 8, 13);
        Part rays = m.bone("wound_rays", wound, -27, 0, 0);
        m.ab(rays, "rays", -61, 0, -13, -27, 0, 13);
        m.ab(rays, "rays", -61, -11, 0, -27, 11, 0);

        // ---- Bauchglühen, wenn er Spieler verschluckt hat (Kerne scheinen durch die Haut)
        Part gorgeC = m.bone("gorge_c", chestSkin, 0, 0, -4);
        m.ab(gorgeC, "gorge", 27, -15, -20, 27, 15, 12);
        m.ab(gorgeC, "gorge", -27, -15, -20, -27, 15, 12);
        m.ab(gorgeC, "gorge", -15, 28, -2, 15, 28, 12);
        Part gorgeB = m.bone("gorge_b", bellySkin, 0, 0, 20);
        m.ab(gorgeB, "gorge", 26, -15, 10, 26, 15, 30);
        m.ab(gorgeB, "gorge", -26, -15, 10, -26, 15, 30);
        m.ab(gorgeB, "gorge", -14, 27, 10, 14, 27, 30);
        return m;
    }

    static void mir(Model m, Part p, String mat, int s, double x0, double y0, double z0, double x1, double y1, double z1) {
        if (s > 0) m.ab(p, mat, x0, y0, z0, x1, y1, z1); else m.ab(p, mat, -x1, y0, z0, -x0, y1, z1);
    }

    /**
     * Kristallsplitter (auch Zähne) aus drei Stufen: wächst vom Fußpunkt entlang [axis] (0 x, 1 y, 2 z) in Richtung [dir].
     */
    static void shard(Model m, Part p, String mat, double x, double y, double z, int axis, int dir, int wid, int len, String key) {
        int h1 = Math.max(1, (int) Math.round(len * 0.45)), h3 = len >= 3 ? Math.max(1, (int) Math.round(len * 0.25)) : 0, h2 = len - h1 - h3;
        int[] ws = {wid, Math.max(1, wid - 1), 1}, hs = {h1, h2, h3};
        double at = 0, k = 0;
        for (int i = 0; i < 3; i++) {
            if (hs[i] <= 0) continue;
            double w = ws[i] / 2.0, a0 = dir > 0 ? at : at - hs[i], a1 = a0 + hs[i];
            Cub c = switch (axis) {
                case 0 -> m.ab(p, mat, x + a0, y - w, z - w, x + a1, y + w, z + w);
                case 1 -> m.ab(p, mat, x - w, y + a0, z - w, x + w, y + a1, z + w);
                default -> m.ab(p, mat, x - w, y - w, z + a0, x + w, y + w, z + a1);
            };
            c.share = key + axis + (dir > 0 ? "p" : "n") + ws[i] + "_" + hs[i];
            double k1 = k + hs[i] / (double) len;
            c.gax = axis;
            if (dir > 0) { c.g0 = k; c.g1 = k1; } else { c.g0 = k1; c.g1 = k; }
            k = k1;
            at += dir * hs[i];
        }
    }

    /** Harpune: steckt bei (x, y, z), Schaft zeigt ungedreht nach oben; [ropes] hängende Seilglieder. */
    static void harpoon(Model m, Part parent, String name, double x, double y, double z, int len, double rx, double rz, int ropes, boolean ring) {
        ENTRIES.add(new double[]{x, y, z});
        Part h = m.bone(name, parent, x, y, z).rot(rx, 0, rz);
        m.ab(h, "wood", x - 0.5, y - len, z - 0.5, x + 0.5, y + 2, z + 0.5).share = "wood" + len;
        m.ab(h, "iron", x - 1, y - 4, z - 1, x + 1, y, z + 1).share = "iron4";
        if (ring) m.ab(h, "iron", x - 1, y - len - 1, z - 1, x + 1, y - len + 1, z + 1).share = "iron2";
        if (ropes > 0) {
            double ry = y - len + 4;
            m.ab(h, "rope", x - 1, ry - 1, z - 1, x + 1, ry + 1, z + 1).share = "knot";
            Part prev = h;
            for (int i = 0; i < ropes; i++) {
                Part r = m.bone(name + "_rope" + (i + 1), prev, x, ry + 7 * i, z + 1);
                boolean last = i == ropes - 1;
                m.ab(r, "rope", x - 0.5, ry + 7 * i, z + 0.5, x + 0.5, ry + 7 * i + (last ? 5 : 7), z + 1.5).share = last ? "rope5" : "rope7";
                if (last) m.ab(r, "rope", x - 1, ry + 7 * i + 5, z, x + 1, ry + 7 * i + 7, z + 2).share = "fray";
                prev = r;
            }
        }
    }

    // =====================================================================================
    // Leviathan: Farben
    /** Umriss entlang der Längsachse: z, halbe Breite, Oberkante, Unterkante. */
    static final double[][] PROF = {
        {-96, 12, -4, 18}, {-88, 18, -15, 21}, {-84, 21, -20, 22}, {-74, 23, -23, 24}, {-62, 24, -25, 25}, {-44, 25, -26, 26}, {-20, 26, -27, 27},
        {14, 26, -27, 27}, {20, 25, -26, 26}, {30, 22, -23, 22}, {38, 21, -22, 20}, {46, 18, -20, 16}, {53, 15, -18, 13}, {60, 12, -15, 9},
        {66, 10, -13, 7}, {72, 8, -11, 5}, {78, 6, -9, 3}, {86, 5, -8, 2}, {110, 4, -6, 0}};

    static double[] prof(double z) {
        if (z <= PROF[0][0]) return PROF[0];
        for (int i = 1; i < PROF.length; i++) if (z <= PROF[i][0]) {
            double k = (z - PROF[i - 1][0]) / (PROF[i][0] - PROF[i - 1][0]);
            double[] a = PROF[i - 1], b = PROF[i];
            return new double[]{z, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k, a[3] + (b[3] - a[3]) * k};
        }
        return PROF[PROF.length - 1];
    }

    /** 0 = Rückenlinie, 1 = Bauchlinie. */
    static double around(double x, double y, double z) {
        double[] p = prof(z);
        double cy = (p[2] + p[3]) / 2, hh = (p[3] - p[2]) / 2;
        return Math.atan2(Math.abs(x) / p[1], -(y - cy) / hh) / Math.PI;
    }

    // Nachthimmel-Haut: fast schwarzer Rücken, tiefviolette Flanken, staubig-malvenfarbener Bauch
    static final double[] GT = {0, 0.18, 0.36, 0.52, 0.66, 0.82, 1};
    static final int[] GC = {0x0b0a25, 0x141240, 0x21195c, 0x33206f, 0x482a78, 0x62407f, 0x7c5a8c};
    static final int[] GAL = {0x3a0f8a, 0x8a2be2, 0xff4fc8, 0xffc4f0, 0x86ecff, 0xffffff};
    static double CLOUD;

    static int skinBase(double x, double y, double z, double t) {
        double tn = t + (fbm(x * 0.05, y * 0.05, z * 0.05, 3) - 0.5) * 0.22;
        int col = grad(tn, GT, GC);
        double n = fbm(x * 0.026 + 9, y * 0.026, z * 0.026, 7);
        double cloud = ramp(n, 0.52, 0.74);
        CLOUD = cloud;
        col = mixc(col, t < 0.5 ? 0x2a46b4 : 0x9a3c9c, cloud * 0.42);
        double n2 = fbm(x * 0.06, y * 0.06 + 4, z * 0.06, 13);
        if (t < 0.65) col = mixc(col, 0x2a9ab8, ramp(n2, 0.64, 0.82) * 0.3);
        double n3 = fbm(x * 0.045 + 20, y * 0.045, z * 0.045, 23);
        col = scale(col, 0.62 + 0.38 * ramp(n3, 0.28, 0.55));
        return col;
    }

    static final int Q = 3;
    static int qs(double v) { return (int) Math.floor(v * Q); }
    static boolean star(double x, double y, double z, double dens) { return hash(qs(x), qs(y), qs(z), 91) < dens; }
    static boolean bigStar(double x, double y, double z, double dens) { return star(x, y, z, dens) && hash(qs(x), qs(y), qs(z), 92) < 0.2; }
    static int starCol(double x, double y, double z) {
        double h = hash(qs(x), qs(y), qs(z), 93);
        return h < 0.5 ? 0xffffff : h < 0.75 ? 0xffc0ee : 0xa8f0ff;
    }

    /** Sterne auf der Haut; die hellsten haben ein kleines Kreuz. */
    static void stars(Tex t, double dens) {
        double h = 1.0 / Q;
        if (star(t.x, t.y, t.z, dens)) {
            double b = hash(qs(t.x), qs(t.y), qs(t.z), 94);
            int c = starCol(t.x, t.y, t.z);
            t.s1 = argb(mixc(t.s1, c, 0.5 + 0.4 * b), 255);
            t.s3 = argb(mixc(t.s3, c, 0.5 + 0.4 * b), 255);
            t.g1 = argb(c, 150 + 105 * b);
            t.g3 = maxGlow(t.g3, argb(c, 150 + 105 * b));
            return;
        }
        double[][] nb = t.nx != 0 ? new double[][]{{0, h, 0}, {0, -h, 0}, {0, 0, h}, {0, 0, -h}}
            : t.ny != 0 ? new double[][]{{h, 0, 0}, {-h, 0, 0}, {0, 0, h}, {0, 0, -h}}
            : new double[][]{{h, 0, 0}, {-h, 0, 0}, {0, h, 0}, {0, -h, 0}};
        for (double[] d : nb) if (bigStar(t.x + d[0], t.y + d[1], t.z + d[2], dens)) {
            int c = starCol(t.x + d[0], t.y + d[1], t.z + d[2]);
            t.s1 = argb(mixc(t.s1, c, 0.25), 255);
            t.g1 = maxGlow(t.g1, argb(c, 110));
            t.g3 = maxGlow(t.g3, argb(c, 110));
            return;
        }
    }

    static int maxGlow(int a, int b) { return (a >>> 24) >= (b >>> 24) ? a : b; }

    /** Sternbilder auf den Flanken: Linienzüge in (z, y). */
    static final double[][][] CONST = {
        {{-52, -13}, {-44, -6}, {-37, -12}, {-29, -7}, {-23, 3}},
        {{-37, -12}, {-34, -1}},
        {{-14, -13}, {-4, -6}, {8, -12}, {12, 2}, {2, 9}, {-4, -6}},
        {{-14, -13}, {-19, -3}},
        {{19, -10}, {28, -4}, {37, -9}, {45, -3}, {55, -7}},
        {{23, 7}, {30, 11}, {36, 5}},
        {{-80, -4}, {-72, 0}, {-68, -8}}};

    static void constellation(Tex t) {
        if (t.nx == 0) return;
        double best = 9, vbest = 9;
        for (double[][] line : CONST) for (int i = 0; i < line.length; i++) {
            double[] a = line[i];
            vbest = Math.min(vbest, Math.hypot(t.z - a[0], t.y - a[1]));
            if (i + 1 < line.length) {
                double[] b = line[i + 1];
                best = Math.min(best, segDist(t.z, t.y, a[0], a[1], b[0], b[1]));
            }
        }
        if (vbest < 1.0) {
            t.s1 = argb(mixc(t.s1, 0xffffff, vbest < 0.6 ? 0.9 : 0.45), 255);
            t.g1 = argb(0xfff4ff, vbest < 0.6 ? 255 : 160);
            t.g3 = maxGlow(t.g3, t.g1);
        } else if (best < 0.3) {
            t.s1 = argb(mixc(t.s1, 0xcdb4ff, 0.32), 255);
            t.g1 = maxGlow(t.g1, argb(0xc4acff, 104));
            t.g3 = maxGlow(t.g3, argb(0xc4acff, 104));
        }
    }

    /** Alte Narben in (z, A): A = Winkel um den Körper mal 90 (0 = Rücken, 90 = Bauch; links positiv). z0, A0, z1, A1, Breite. */
    static final double[][] SCARS = {
        {-86, -10, -62, 24, 1.5}, {-78, 4, -66, -16, 1.0}, {-68, 27, -52, 45, 1.1},
        {14, -30, 36, -50, 1.2}, {19, -26, 41, -46, 1.2}, {24, -22, 45, -42, 1.1},
        {-16, 28, 2, 34, 1.2}, {2, 34, 22, 29, 1.1}, {-6, 70, 14, 62, 1.0}, {2, -72, 20, -66, 1.0},
        {50, -75, 50, 75, 0.9}, {52.5, -40, 52.5, 62, 0.6}, {-30, -8, -12, 10, 1.1}, {-27, 9, -14, -6, 0.9},
        {-90, 62, -70, 76, 1.0}, {-80, -66, -64, -58, 1.0}, {30, 12, 44, -6, 1.0}, {-40, -40, -26, -52, 1.1}, {62, 20, 76, 44, 0.8}};

    /** Narbe an dieser Stelle: 0 = Mitte, 1 = Rand, darüber heil. */
    static double scar(double z, double A) {
        double best = 9;
        for (double[] s : SCARS) best = Math.min(best, segDist(z, A, s[0], s[1], s[2], s[3]) / s[4]);
        // viele kleine Kratzer
        int ci = (int) Math.floor(z / 15), cj = (int) Math.floor(A / 15);
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            int a = ci + di, b = cj + dj;
            if (hash(a, b, 0, 211) > 0.3) continue;
            double cx = (a + hash(a, b, 0, 212)) * 15, cy = (b + hash(a, b, 0, 213)) * 15;
            double ang = hash(a, b, 0, 214) * Math.PI, len = 2.5 + 5 * hash(a, b, 0, 215), w = 0.3 + 0.3 * hash(a, b, 0, 216);
            double dx = Math.cos(ang) * len, dy = Math.sin(ang) * len;
            best = Math.min(best, segDist(z, A, cx - dx, cy - dy, cx + dx, cy + dy) / w);
        }
        return best;
    }

    static double CORE;
    /** Phase 3: Risse in der Haut (0 = heil, 1 = Rissmitte). */
    static double crack(double x, double y, double z) {
        double[] v = voronoi(x / 22, y / 22, z / 22, 77);
        double gate = fbm(x * 0.03 + 5, y * 0.03, z * 0.03, 5);
        // um die Ausbruchwunde herum platzt die Haut besonders weit auf
        double near = 1 - clamp(Math.hypot(Math.hypot(x + 26, y), z) / 34, 0, 1);
        double width = 0.026 + 0.2 * ramp(gate, 0.47, 0.66) + 0.16 * near;
        double jag = (vnoise(x * 0.9, y * 0.9, z * 0.9, 41) - 0.5) * 0.05;
        return clamp(1 - (v[1] - v[0] + jag) / width, 0, 1);
    }

    static int galaxy(double x, double y, double z) {
        double n = fbm(x * 0.09, y * 0.09, z * 0.09, 61);
        double sw = fbm(x * 0.03 + n * 2, y * 0.03, z * 0.03 - n * 2, 67);
        return grad(clamp(sw * 1.5 - 0.2 + (n - 0.5) * 0.5, 0, 1), new double[]{0, 0.25, 0.45, 0.62, 0.8, 1}, GAL);
    }

    static void whalePaint(Tex t) {
        String m = t.c.mat;
        switch (m) {
            case "flare" -> { flare(t); return; }
            case "heart" -> { heart(t); return; }
            case "tooth", "crystal" -> { crystal(t); return; }
            case "eye" -> { eye(t); return; }
            case "halo" -> { halo(t); return; }
            case "tglow", "core", "blowglow" -> { ember(t); return; }
            case "tongue" -> { mouth(t, true); return; }
            case "wood", "iron", "rope" -> { gear(t); return; }
            case "bone" -> { bone(t); return; }
            case "woundglow" -> { woundGlow(t); return; }
            case "rays" -> { rays(t); return; }
            case "gorge" -> { gorge(t); return; }
            default -> { }
        }
        String pn = t.c.part.name;
        boolean inside = switch (m) {
            case "head" -> t.ny > 0 && t.y > 3.5;
            case "jaw" -> (t.ny < 0 && t.y > 16.5) || (t.nx != 0 && t.nx * t.x < 0 && Math.abs(t.x) > 1) || (t.nz > 0 && t.y < 17.5 && t.z < -90);
            case "cheek", "flap" -> t.nx != 0 && (m.equals("cheek") ? t.nx * t.x < 0 : t.nx > 0);
            default -> pn.equals("neck_skin") && m.equals("skin") && t.f == F.FRONT && Math.abs(t.x) < 20 && t.y > 4 && t.y < 25;
        };
        if (inside) { mouth(t, false); return; }

        boolean fin = m.equals("fin") || m.equals("fluke");
        boolean bodyLike = m.equals("skin") || m.equals("head") || m.equals("jaw") || m.equals("bulge") || m.equals("flap") || m.equals("cheek");
        double tt, span = 0;
        if (fin) {
            span = m.equals("fluke") ? clamp(Math.abs(t.x) / 37, 0, 1) : clamp((Math.abs(t.x) - 23) / 44, 0, 1);
            tt = (t.ny < 0 ? 0.14 : t.ny > 0 ? 0.68 : 0.4) + span * 0.12;
        } else if (m.equals("hump") || m.equals("blow") || m.equals("brow")) tt = 0.04;
        else if (m.equals("sac") || m.equals("slat")) tt = 0.94;
        else tt = around(t.x, t.y, t.z);

        int col = skinBase(t.x, t.y, t.z, tt);
        double cloud = CLOUD;
        int glow = argb(tt < 0.5 ? 0x3c56e0 : 0xb04cb0, 14 + cloud * 66);
        boolean plain = false; // keine Sterne (Narbe, Kruste, Furche)

        // ---- alte Lederhaut: Zellen mit dunklen Fugen, längs laufende Runzeln hinter dem Kopf
        double[] v = voronoi(t.x / 5.5, t.y / 5.5, t.z / 5.5, 131);
        double hide = 1 - ramp(v[1] - v[0], 0.02, 0.11);
        double hideAmt = 0.25 + 0.75 * ramp(fbm(t.x * 0.03, t.y * 0.03 + 7, t.z * 0.03, 37), 0.35, 0.6);
        col = scale(col, (1 - 0.24 * hide * hideAmt) * (0.93 + 0.14 * VCELL));
        if (!fin) {
            double w = fbm(t.x * 0.33, t.y * 0.33, t.z * 0.05, 19);
            double crease = 1 - ramp(Math.abs(w - 0.5), 0.0, 0.035);
            double amt = ramp(t.z, -52, -24) * (0.45 + 0.55 * ramp(fbm(t.x * 0.02, t.y * 0.02, t.z * 0.02, 29), 0.3, 0.6));
            col = scale(col, 1 - 0.32 * crease * amt);
        }
        // ---- bleiche Altersflecken wie beim weißen Wal: an der Stirn am stärksten
        double pale = fbm(t.x * 0.045 + 3, t.y * 0.045, t.z * 0.045, 43) + 0.2 * ramp(-t.z, 44, 92) * (tt < 0.6 ? 1 : 0.3) + (m.equals("hump") ? 0.2 : 0);
        double pk = ramp(pale, 0.58, 0.76);
        if (pk > 0) {
            double sp = fbm(t.x * 0.3, t.y * 0.3, t.z * 0.3, 47);
            col = mixc(col, mixc(0x6c68a8, 0xa49ed0, sp), pk * (0.2 + 0.22 * sp));
        }

        // ---- Kehlfurchen vom Kinn bis hinter die Brust; darin glimmt es
        double[] p = prof(t.z);
        if ((m.equals("skin") || m.equals("jaw") || m.equals("sac")) && t.z < 24) {
            double s = Math.abs(t.x) + Math.max(0, p[3] - t.y);
            double fr = s - 4 * Math.floor(s / 4);
            double strength = ramp(tt, 0.60, 0.70) * (t.z < 6 ? 1 : ramp(fbm(t.x * 0.2, 0, t.z * 0.08, 31), 0.35 + (t.z - 6) * 0.02, 0.4 + (t.z - 6) * 0.02));
            if (m.equals("sac")) strength = 1;
            if (strength > 0.01) {
                if (fr < 1.0) {
                    col = mixc(col, 0x2a0c3c, 0.8 * strength);
                    glow = maxGlow(glow, argb(0xff58c8, 96 * strength));
                    plain = true;
                } else if (fr < 1.4) col = mixc(col, 0xb89ad0, 0.3 * strength);
            }
        }
        if (m.equals("slat")) {
            // Lamelle: heller Wulst mit dunklen Kanten
            double e = Math.abs(t.fu - 0.5) * 2;
            col = mixc(col, 0xb89ad0, 0.22 * (1 - e));
            if (e > 0.72 || t.nx != 0) { col = mixc(col, 0x2a0c3c, 0.75); glow = maxGlow(glow, argb(0xff58c8, 80)); plain = true; }
        }
        if (fin) {
            double fr = t.z - 4 * Math.floor(t.z / 4);
            if (fr < 0.5 && t.ny != 0) col = scale(col, 0.8);
            if (t.fw > 0.88) col = mixc(col, 0x9a86c8, 0.4);
            glow = maxGlow(glow, argb(0xc07ae4, 70 * span * span));
        }

        // ---- Narben und Kratzer
        if (bodyLike || fin) {
            double wz = (fbm(t.x * 0.07, t.y * 0.07, t.z * 0.07, 241) - 0.5) * 9, wa = (fbm(t.x * 0.07, t.y * 0.07, t.z * 0.07, 243) - 0.5) * 9;
            double k = bodyLike ? scar(t.z + wz, around(t.x, t.y, t.z) * 90 * (t.x >= 0 ? 1 : -1) + wa) : scar(t.z * 1.3 + 300 + wz, t.x * 1.3 + (t.ny > 0 ? 500 : 0) + wa);
            if (k < 1) {
                col = mixc(col, mixc(0xcfc2ea, 0x9a8cc0, vnoise(t.x * 1.5, t.y * 1.5, t.z * 1.5, 53)), 0.82 - 0.3 * k);
                plain = true;
                t.g3 = argb(0xff9ae4, 110 * (1 - k));
            } else if (k < 1.7) { col = mixc(col, 0x12061e, 0.55 * (1.7 - k) / 0.7); plain = true; }
        }

        // ---- Sternenkruste (Seepocken): Schnauze, Lippen, Kinn, Flossenkanten, Höcker, um die Harpunen
        double cm = 0;
        if (m.equals("head")) cm = Math.max(0.8 * ramp(-t.z, 82, 90), t.ny == 0 && t.y > 0 ? 0.7 : 0);
        else if (m.equals("jaw")) cm = Math.max(0.8 * ramp(-t.z, 86, 95), t.y < 10 ? 0.65 : 0);
        else if (m.equals("fin")) cm = t.fw < 0.16 ? 0.8 : 0;
        else if (m.equals("fluke")) cm = t.fw < 0.1 ? 0.7 : 0;
        else if (m.equals("hump") || m.equals("brow")) cm = 1;
        else if (m.equals("blow")) cm = 0.6;
        if (bodyLike || m.equals("hump")) for (double[] e : ENTRIES) cm = Math.max(cm, 1 - Math.hypot(Math.hypot(t.x - e[0], t.y - e[1]), t.z - e[2]) / 7);
        cm *= m.equals("brow") ? 1 : 0.2 + 0.8 * ramp(fbm(t.x * 0.11, t.y * 0.11, t.z * 0.11, 59), 0.38, 0.6);
        if (cm > 0.3) {
            double[] c = voronoi(t.x / 1.7, t.y / 1.7, t.z / 1.7, 61);
            double cell = VCELL, e = c[1] - c[0];
            int cc = cell < 0.1 ? mixc(0x6a3cc0, 0xc8a8ff, 1 - c[0]) : mixc(0x4c4678, 0x9088bc, cell);
            if (e < 0.12) cc = 0x1c1030;
            else if (c[0] < 0.2) cc = scale(cc, 0.6);
            col = mixc(col, cc, clamp((cm - 0.3) * 3, 0, 1) * (e < 0.12 ? 0.8 : 0.62));
            if (cell < 0.1 && e >= 0.12) glow = maxGlow(glow, argb(0xb48cf8, 130));
            plain = true;
        }

        // ---- Lippen- und Zahnfleischkante, Augenhöhle, Blaslochrand
        if (m.equals("head") && t.ny == 0 && t.y > 2.6) col = mixc(col, 0x1c0a2c, 0.75);
        if (m.equals("jaw") && t.y < 9) col = mixc(col, 0x1c0a2c, 0.75);
        if (m.equals("cheek")) col = mixc(col, 0x1c0a2c, 0.5);
        if (m.equals("head") && t.nx != 0) {
            double d = Math.hypot(t.y - EYE_Y, t.z - (EYE_Z - 0.5));
            if (d < 9) { col = scale(col, 0.4 + 0.6 * d / 9); plain = plain || d < 5; }
        }
        if (m.equals("blow") && (t.fu < 0.12 || t.fu > 0.88 || t.fw < 0.1 || t.fw > 0.9)) col = mixc(col, 0x120820, 0.6);
        // Fassung des Sternenherzens: Licht sickert in die Haut
        if (t.ny > 0 && m.equals("skin")) {
            double d = Math.hypot(t.x - HEART_X, t.z - HEART_Z);
            if (d < 19) {
                double k = 1 - d / 19;
                double ray = Math.pow(Math.abs(Math.cos(Math.atan2(t.z - HEART_Z, t.x - HEART_X) * 4)), 3);
                col = mixc(col, 0xffa8e6, k * (0.35 + 0.4 * ray));
                glow = maxGlow(glow, argb(0xff8ee0, 220 * k * (0.4 + 0.6 * ray)));
            }
        }
        if (m.equals("heartray")) {
            double d = Math.hypot(t.x - HEART_X, t.z - HEART_Z) / 19;
            col = mixc(0xffe2f6, col, clamp(d * d, 0, 1));
            glow = argb(0xffc8f0, 255 * (1 - d * 0.8));
            plain = true;
        }
        if (m.equals("bulge")) {
            // zum Zerreißen gespannt: bleich, glühende Adern
            double[] c = voronoi(t.x / 4, t.y / 4, t.z / 4, 171);
            double e = 1 - ramp(c[1] - c[0], 0.0, 0.16);
            col = mixc(mixc(col, 0xd6a4e0, 0.45), 0xfff0ff, e * 0.9);
            glow = argb(0xff9ae8, 60 + 195 * e);
            plain = true;
        }
        if (m.equals("flap")) { col = scale(col, 0.8); if (t.nx == 0) col = mixc(col, 0x6a1440, 0.7); }
        col = scale(col, 0.96 + 0.08 * hash(qs(t.x), qs(t.y), qs(t.z), 2));
        t.s1 = argb(col, 255);
        t.g1 = glow;

        // ---- Phase 3: Haut reißt auf, dahinter die Galaxie; alte Narben glühen
        int c3 = scale(mixc(col, 0x4a1060, 0.2), 0.8);
        int g3 = maxGlow(argb(glow, (glow >>> 24) * 1.4), t.g3);
        double k = m.equals("heartray") || m.equals("bulge") ? 0 : crack(t.x, t.y, t.z);
        if (k > 0) {
            int gal = galaxy(t.x, t.y, t.z);
            if (k > 0.3) {
                c3 = mixc(0x0a0420, gal, 0.3 + 0.3 * k);
                g3 = argb(mixc(gal, 0xffffff, ramp(k, 0.7, 1) * 0.6), 255 * clamp(0.55 + k * 0.6, 0, 1));
            } else {
                c3 = mixc(c3, 0xffc6ee, k / 0.3 * 0.75);
                g3 = maxGlow(g3, argb(0xff9ae4, 200 * k / 0.3));
            }
        }
        t.s3 = argb(c3, 255);
        t.g3 = g3;
        if (k <= 0.3 && !plain && !m.equals("slat") && !m.equals("sac")) {
            // Sternenband quer über die Flanken: dort stehen sie dichter
            double band = ramp(fbm(t.x * 0.02 + 40, t.y * 0.035, t.z * 0.02, 83), 0.45, 0.7);
            stars(t, (tt < 0.75 ? 0.0045 : 0.002) * (0.5 + 2.2 * band));
            if (m.equals("skin") || m.equals("head")) constellation(t);
        }
    }

    static void mouth(Tex t, boolean tongue) {
        double n = fbm(t.x * 0.15, t.y * 0.15, t.z * 0.15, 51);
        int col = mixc(0x1c0626, 0x5c1450, n);
        double deep = ramp(t.z, -90, -48);
        String pn = t.c.part.name;
        if (tongue) {
            col = mixc(0x6c1e62, 0xb0488e, n);
            if (Math.abs(t.x) < 0.6) col = scale(col, 0.65);
            double fr = t.z - 5 * Math.floor(t.z / 5);
            if (fr < 0.7) col = scale(col, 0.82);
        } else if (t.ny != 0) {
            // Gaumenleisten quer
            double fr = t.z - 3 * Math.floor(t.z / 3);
            if (fr < 0.7) col = mixc(col, 0x8c2e7a, 0.6);
            if (t.ny > 0 && Math.abs(t.x) < 1) col = mixc(col, 0xb04890, 0.5);
        } else {
            double fr = t.y - 3 * Math.floor(t.y / 3);
            if (fr < 0.6) col = scale(col, 0.75);
        }
        int glow = argb(0xff78d2, 120 * deep * deep);
        int glow3 = argb(0xa8ecff, 170 * deep);
        if (pn.equals("neck_skin")) {
            // Rachen: glühender Schlund mit dunklem Ring
            double r = clamp(Math.hypot(t.x / 22, (t.y - 14) / 13), 0, 1);
            double swirl = fbm(t.x * 0.2, t.y * 0.2, 3, 57);
            col = mixc(mixc(0xffb6ea, 0xff60c8, swirl), 0x2c0838, ramp(r, 0.25, 1));
            glow = argb(mixc(0xffd8f6, 0xe040b0, r), 255 - 170 * r);
            glow3 = argb(mixc(0xffffff, 0x5ad8ff, r), 255 - 120 * r);
        }
        if (t.c.mat.equals("flap") || t.c.mat.equals("cheek")) {
            // rohes Fleisch mit glimmenden Adern
            double[] c = voronoi(t.x / 3, t.y / 3, t.z / 3, 181);
            double e = 1 - ramp(c[1] - c[0], 0.0, 0.14);
            col = mixc(mixc(0x4a0e30, 0x8a1e58, n), 0xff9ae0, e * 0.6);
            glow = argb(0xff78d2, t.c.mat.equals("flap") ? 40 + 180 * e : 60 * e);
            glow3 = glow;
        }
        t.s1 = argb(col, 255); t.g1 = glow;
        t.s3 = argb(mixc(col, 0x8fdcff, deep * 0.25), 255); t.g3 = glow3;
    }

    /** Wert entlang des Kristalls: 0 = Wurzel, 1 = Spitze. */
    static double along(Tex t) {
        Cub c = t.c;
        double f = c.gax == 0 ? t.fu : c.gax == 1 ? t.fv : t.fw;
        // Stirnflächen liegen ganz an einem Ende
        if ((c.gax == 0 && t.nx != 0) || (c.gax == 1 && t.ny != 0) || (c.gax == 2 && t.nz != 0)) f = f < 0.5 ? 0 : 1;
        return c.g0 + (c.g1 - c.g0) * f;
    }

    /** Kristallzähne (Elfenbein bis Eisblau) und Amethyst-Kruste. */
    static void crystal(Tex t) {
        boolean tooth = t.c.mat.equals("tooth");
        double k = along(t);
        // Facetten: je Seite ein anderer Ton, feine Längsadern
        double facet = t.nx > 0 ? 1.06 : t.nx < 0 ? 0.84 : t.nz != 0 ? 0.94 : 1.0;
        double a = t.c.gax == 1 ? (t.nx != 0 ? t.z : t.x) : (t.c.gax == 0 ? (t.ny != 0 ? t.z : t.y) : (t.nx != 0 ? t.y : t.x));
        double vein = hash(qs(a), t.c.w * 7 + t.c.h, t.f.ordinal(), 9) < 0.28 ? 0.86 : 1;
        int col, col3;
        if (tooth) {
            col = grad(k, new double[]{0, 0.3, 0.7, 1}, new int[]{0x4a3676, 0xa898d6, 0xe2dcf6, 0xffffff});
            col3 = grad(k, new double[]{0, 0.3, 0.7, 1}, new int[]{0x2c3a86, 0x84b4e6, 0xd8f2ff, 0xffffff});
        } else {
            col = grad(k, new double[]{0, 0.35, 0.75, 1}, new int[]{0x241250, 0x5c34b4, 0xa880f4, 0xf0e4ff});
            col3 = grad(k, new double[]{0, 0.35, 0.75, 1}, new int[]{0x2a1060, 0x8a34c4, 0xf07ae0, 0xfff0ff});
        }
        col = scale(col, facet * vein);
        col3 = scale(col3, facet * vein);
        t.s1 = argb(col, 255); t.s3 = argb(col3, 255);
        double g = tooth ? 26 + 120 * k * k : 30 + 190 * k * k;
        t.g1 = argb(tooth ? 0xf0e8ff : 0xc49cff, g);
        t.g3 = argb(tooth ? 0xd8f4ff : 0xff9ae8, Math.min(255, g * 1.5 + 30));
    }

    static void eye(Tex t) {
        int rim = 0x100620;
        t.s1 = t.s3 = argb(rim, 255);
        if (t.nx == 0) return;
        double e = Math.pow((t.fw - 0.5) / 0.5, 2) + Math.pow((t.fv - 0.5) / 0.5, 2);
        if (e > 1.05) return;
        boolean pupil = Math.abs(t.fw - 0.5) < 0.1 && Math.abs(t.fv - 0.5) < 0.42;
        int iris = mixc(0xffffff, 0xff9ae0, clamp(e * 1.1, 0, 1)), iris3 = mixc(0xffffff, 0x60dcff, clamp(e * 1.1, 0, 1));
        if (pupil) { iris = 0x24083c; iris3 = 0x081c3c; }
        t.s1 = argb(iris, 255); t.s3 = argb(iris3, 255);
        t.g1 = argb(iris, pupil ? 0 : 255); t.g3 = argb(iris3, pupil ? 0 : 255);
    }

    /** Lichthof ums Auge: nur in der Leuchtschicht, damit man die Augen von weitem sieht. */
    static void halo(Tex t) {
        if (t.nx == 0) return;
        double a = (t.fw - 0.5) * 2, b = (t.fv - 0.5) * 2;
        double r = Math.hypot(a, b);
        if (r >= 1) return;
        double base = Math.pow(1 - r, 2.4);
        double spike = Math.pow(Math.max(0, 1 - Math.abs(b) * 9), 2) * (1 - Math.abs(a)) * 0.8 + Math.pow(Math.max(0, 1 - Math.abs(a) * 9), 2) * (1 - Math.abs(b)) * 0.45;
        double i = clamp(base * 0.75 + spike, 0, 1);
        t.g1 = argb(mixc(0xff7ad6, 0xffffff, i), 210 * i);
        t.g3 = argb(mixc(0x58d0ff, 0xffffff, i), 240 * i);
    }

    /** Glut: Schlund, Kern unter den Kehlfalten, Schlot des Blaslochs. */
    static void ember(Tex t) {
        String m = t.c.mat;
        double r;
        if (m.equals("tglow")) r = clamp(Math.hypot(t.x / 12, (t.y - 15) / 6), 0, 1);
        else if (m.equals("blowglow")) r = clamp(Math.hypot(t.x / 4, (t.z + 35) / 3), 0, 1) * 0.8;
        else r = 0.25 + 0.5 * fbm(t.x * 0.4, t.y * 0.4, t.z * 0.12, 151);
        int c = mixc(0xffe6fa, 0xff4cbc, clamp(r, 0, 1));
        int c3 = mixc(0xffffff, 0x6ae0ff, clamp(r, 0, 1));
        if (m.equals("blowglow")) { c = mixc(0xffffff, 0x8ad8ff, r); c3 = c; }
        if (m.equals("tglow")) {
            // Schlund: heller Kern, wirbelnde Glut, nach außen dunkel (kein flaches Rechteck)
            double sw = fbm(t.x * 0.35, t.y * 0.35, 4, 153);
            double rr = clamp(r + (sw - 0.5) * 0.35, 0, 1);
            c = mixc(mixc(0xfff0fc, 0xff5cc8, ramp(rr, 0, 0.5)), 0x7a2266, ramp(rr, 0.45, 1));
            c3 = mixc(mixc(0xffffff, 0x6ae0ff, ramp(rr, 0, 0.5)), 0x3a3a7a, ramp(rr, 0.45, 1));
            double a = 255 * Math.pow(1 - ramp(rr, 0.2, 1), 1.3);
            t.s1 = argb(c, 255); t.g1 = argb(c, a);
            t.s3 = argb(c3, 255); t.g3 = argb(c3, a);
            return;
        }
        t.s1 = argb(c, 255); t.g1 = argb(c, 255);
        t.s3 = argb(c3, 255); t.g3 = argb(c3, 255);
    }

    static void heart(Tex t) {
        double b = hash((int) Math.floor(t.x / 2 + 50), (int) Math.floor(t.y / 2), (int) Math.floor(t.z / 2 + 50), 71);
        double edge = Math.max(Math.abs(t.x - HEART_X), Math.abs(t.z - HEART_Z)) / 7;
        int c = mixc(mixc(0xffffff, 0xff86dc, edge * edge * 0.8), 0xc8f4ff, b * 0.35);
        int c3 = mixc(mixc(0xffffff, 0x70dcff, edge * edge * 0.8), 0xffc8f4, b * 0.35);
        t.s1 = argb(c, 255); t.g1 = argb(c, 255);
        t.s3 = argb(c3, 255); t.g3 = argb(c3, 255);
    }

    /** Lichtkranz des Herzens: in der Haut-Textur unsichtbar, leuchtet nur in der Glow-Schicht. */
    static void flare(Tex t) {
        double a, b;
        if (t.ny != 0) { a = t.x - HEART_X; b = t.z - HEART_Z; }
        else if (t.nz != 0) { a = t.x - HEART_X; b = t.y - HEART_Y; }
        else { a = t.z - HEART_Z; b = t.y - HEART_Y; }
        double r = Math.hypot(a, b) / 17;
        if (r >= 1) return;
        double ang = Math.atan2(b, a);
        double base = Math.pow(1 - r, 1.7);
        double spike = Math.pow(Math.abs(Math.cos(ang * 2)), 14) * (1 - r) * 0.9 + Math.pow(Math.abs(Math.sin(ang * 2)), 24) * Math.pow(1 - r, 2) * 0.6;
        double i = clamp(base * 0.9 + spike, 0, 1);
        t.g1 = argb(scale(mixc(0xff58c8, 0xffffff, i), 0.3 + 0.7 * i), 235 * i);
        t.g3 = argb(scale(mixc(0x58c8ff, 0xffffff, i), 0.3 + 0.7 * i), 235 * i);
    }

    /** Harpunen: verwittertes Holz, Eisen, Hanfseil. */
    static void gear(Tex t) {
        String m = t.c.mat;
        int col;
        double n = fbm(t.x * 1.7, t.y * 0.25, t.z * 1.7, 191);
        if (m.equals("wood")) {
            col = mixc(0x2e2226, 0x6e5546, n);
            col = mixc(col, 0x54506e, 0.3);
            double fr = t.y - 6 * Math.floor(t.y / 6);
            if (fr < 0.4) col = scale(col, 0.6);
            if (hash(qs(t.x), (int) Math.floor(t.y * 0.7), qs(t.z), 193) < 0.12) col = scale(col, 0.7);
        } else if (m.equals("iron")) {
            col = mixc(0x24222e, 0x77748c, fbm(t.x * 0.9, t.y * 0.9, t.z * 0.9, 195));
            if (hash(qs(t.x), qs(t.y), qs(t.z), 197) < 0.18) col = mixc(col, 0x7a3a4e, 0.6);
            if (t.fv < 0.12 || t.fv > 0.88) col = scale(col, 1.25);
        } else {
            double tw = t.y * 1.6 + (t.nx != 0 ? t.z : t.x) * 1.2;
            col = tw - 2 * Math.floor(tw / 2) < 1 ? 0xb6a688 : 0x86765c;
            col = mixc(col, 0x6a5e78, 0.25);
            col = scale(col, 0.85 + 0.3 * n);
        }
        t.s1 = argb(col, 255);
        t.s3 = argb(scale(col, 0.9), 255);
    }

    static void bone(Tex t) {
        double n = fbm(t.x * 0.6, t.y * 0.6, t.z * 0.6, 201);
        int col = mixc(0xb8aa9c, 0xf2e8d8, n);
        col = mixc(col, 0x7a2a5a, clamp((t.x + 28) / 4, 0, 1) * 0.7);
        if (t.nx == 0 && hash(qs(t.x), 0, qs(t.z + t.y), 203) < 0.2) col = scale(col, 0.85);
        t.s1 = t.s3 = argb(col, 255);
        t.g1 = t.g3 = argb(0xffc8f0, 40);
    }

    /** Aufgerissene Flanke: ausgefranstes Loch, dahinter die Galaxie. */
    static void woundGlow(Tex t) {
        if (t.nx == 0) return;
        double r = Math.hypot(t.z / 14, t.y / 12) + (fbm(t.z * 0.28, t.y * 0.28, 5, 221) - 0.5) * 0.7;
        // Zacken am Rand
        r += (vnoise(t.z * 1.1, t.y * 1.1, 2, 223) - 0.5) * 0.25;
        if (r > 0.98) return;
        int gal = galaxy(t.z * 1.6 + 40, t.y * 1.6, 9);
        if (r > 0.78) {
            int c = mixc(0x5a0e36, 0x1a0418, (r - 0.78) / 0.2);
            t.s1 = t.s3 = argb(c, 255);
            t.g1 = t.g3 = argb(0xff6ac8, 150 * (1 - (r - 0.78) / 0.2));
        } else {
            double core = 1 - r / 0.78;
            int c = mixc(mixc(0x12062c, gal, 0.55 + 0.45 * core), 0xffffff, core * core * 0.5);
            t.s1 = t.s3 = argb(c, 255);
            t.g1 = t.g3 = argb(mixc(gal, 0xffffff, core * 0.6), 190 + 65 * core);
        }
        if (star(t.z, t.y, 3, 0.03)) { t.s1 = t.s3 = argb(0xffffff, 255); t.g1 = t.g3 = argb(0xffffff, 255); }
    }

    /** Lichtbahnen aus der Wunde: nur Leuchtschicht. */
    static void rays(Tex t) {
        double d = clamp((-27 - t.x) / 34, 0, 1);
        double across = t.ny != 0 ? t.z / 13 : t.y / 11;
        if (t.nx != 0) return;
        double streak = fbm(across * 7 + 3, t.x * 0.03, t.ny != 0 ? 1 : 8, 231);
        double i = Math.pow(1 - d, 1.4) * ramp(streak, 0.3, 0.72) * (1 - Math.abs(across) * (0.4 + 0.6 * (1 - d)));
        i = clamp(i * 1.3, 0, 1);
        if (i < 0.02) return;
        t.g1 = t.g3 = argb(mixc(0xff5ccc, 0xffffff, i), 220 * i);
    }

    /** Bauchglühen: drei Kerne als Lichtflecken, davor die Schatten der Rippen. Nur Leuchtschicht. */
    static void gorge(Tex t) {
        double a = t.z, b = t.ny != 0 ? t.x * 0.9 : t.y;
        double[][] cores = {{-9, 1}, {7, -4}, {22, 3}};
        double i = 0;
        for (double[] c : cores) { double d2 = (a - c[0]) * (a - c[0]) + (b - c[1]) * (b - c[1]); i += Math.exp(-d2 / 46); }
        double edge = t.ny != 0 ? Math.min(t.fu, 1 - t.fu) : Math.min(t.fv, 1 - t.fv);
        i *= ramp(edge, 0, 0.25) * ramp(Math.min(t.fw, 1 - t.fw), 0, 0.12 );
        // Rippen
        double rib = a + b * 0.25;
        double fr = Math.abs(rib - 7 * Math.floor(rib / 7) - 3.5);
        i *= 0.4 + 0.6 * ramp(fr, 0.5, 1.7);
        i = clamp(i, 0, 1);
        if (i < 0.03) return;
        t.g1 = t.g3 = argb(mixc(0xd03c9c, 0xffe0f4, i * i), 235 * i);
    }

    // =====================================================================================
    // Sternling: 16 Einheiten = 1 Block, Mitte 5 über dem Boden (y = 19)
    static Model starling() {
        Model m = new Model("starling", 64, 64, 1);
        Part ray = m.bone("ray", null, 0, 19.5, 0);
        m.ab(ray, "ray", -3, 18, -5, 3, 21, 5);
        m.ab(ray, "ray", -2, 17, -3, 2, 18, 3);
        m.ab(ray, "ray", -3, 19, -8, -1, 21, -5);
        m.ab(ray, "ray", 1, 19, -8, 3, 21, -5);
        for (int s = -1; s <= 1; s += 2) {
            Part w = m.bone(s > 0 ? "wing_l" : "wing_r", ray, s * 3, 19.5, 0);
            mir(m, w, "wing", s, 3, 19, -5, 9, 20, 5);
            mir(m, w, "wing", s, 9, 19, -4, 13, 20, 4);
            Part w2 = m.bone(s > 0 ? "wing_l2" : "wing_r2", w, s * 13, 19.5, 0);
            mir(m, w2, "wing", s, 13, 19, -3, 17, 20, 3);
            mir(m, w2, "wing", s, 17, 19, -1, 20, 20, 3);
            mir(m, w2, "wing", s, 20, 19, 1, 22, 20, 3);
        }
        Part t1 = m.bone("tail1", ray, 0, 19.5, 5);
        m.ab(t1, "tail", -0.5, 19, 5, 0.5, 20, 10);
        Part t2 = m.bone("tail2", t1, 0, 19.5, 10);
        m.ab(t2, "tail", -0.5, 19, 10, 0.5, 20, 15);
        Part t3 = m.bone("tail3", t2, 0, 19.5, 15);
        m.ab(t3, "tail", -0.5, 19, 15, 0.5, 20, 19);
        m.ab(t3, "tip", -1, 18.5, 19, 1, 20.5, 21);
        return m;
    }

    static void starlingPaint(Tex t) {
        String m = t.c.mat;
        double r = clamp(Math.abs(t.x) / 22, 0, 1);
        int col, glow = 0;
        if (m.equals("tip")) {
            col = 0xffffff; glow = argb(0xfff0ff, 255);
        } else if (m.equals("tail")) {
            double k = clamp((t.z - 5) / 14, 0, 1);
            col = mixc(0x3a2494, 0xff9ae0, k);
            glow = argb(0xffa8e8, 200 * k);
        } else {
            boolean top = t.ny < 0, bottom = t.ny > 0;
            col = top ? mixc(0x3a2aa0, 0xa850e0, r) : bottom ? mixc(0xf6d2ee, 0xff9ada, r) : mixc(0x4a2aa0, 0xc050c0, r);
            double n = hash((int) Math.floor(t.x), (int) Math.floor(t.y), (int) Math.floor(t.z), 5);
            col = scale(col, 0.9 + 0.2 * n);
            if (top && n > 0.955) { col = 0xe8d8ff; glow = argb(0xf0e0ff, 230); }
            // Flügelsaum und Spitzen leuchten
            if (m.equals("wing")) {
                if (t.nz > 0 || (t.ny != 0 && t.z > t.c.z0 + t.c.d - 1) || r > 0.86) { col = mixc(col, 0xffd0f4, 0.65); glow = argb(0xff9ee6, 220); }
                else if (top) glow = maxGlow(glow, argb(0xb070ff, 60 + 60 * r));
            }
            // Stern auf dem Rücken
            if (top && m.equals("ray")) {
                if ((Math.abs(t.x) < 1 && Math.abs(t.z) < 3) || (Math.abs(t.z) < 1 && Math.abs(t.x) < 2)) { col = 0xffffff; glow = argb(0xffffff, 255); }
            }
            // Augen an den Kopflappen
            if (m.equals("ray") && t.nz < 0 && t.z < -7 && t.y < 20 && Math.abs(t.x) > 2) { col = 0xffffff; glow = argb(0xc8f8ff, 255); }
        }
        t.s1 = argb(col, 255); t.g1 = glow;
    }

    // =====================================================================================
    // Sternenkern (Organ im Bauch): 16 Einheiten = 1 Block, Boden bei y = 24, Entity 2,4 × 3,4 Blöcke.
    // Kristallherz in einer Fleischkapsel aus vier Blütenblättern, Kragen, Sehnen in den Boden und nach oben,
    // drei kreisende Splitter, Lichtkranz.
    static final double CORE_Y = -5;

    static Model core() {
        Model m = new Model("core", 224, 224, 2);
        Part root = m.bone("root", null, 0, 24, 0);
        // Kragen aus Fleisch, auf dem der Kern sitzt (wurzelt im Hügel)
        Part collar = m.bone("collar", root, 0, 14, 0);
        m.ab(collar, "flesh", -10, 10, -6, 10, 15, 6);
        m.ab(collar, "flesh", -6, 10, -10, 6, 15, 10);
        m.ab(collar, "flesh", -8, 9, -8, 8, 16, 8);
        m.ab(collar, "flesh", -7, 16, -4, 7, 24, 4);
        m.ab(collar, "flesh", -4, 16, -7, 4, 24, 7);
        m.ab(collar, "flesh", -6, 16, -6, 6, 24, 6);
        // Kristallherz: Doppelpyramide, um 45° gedreht (Facetten zeigen zur Seite)
        Part heart = m.bone("heart", root, 0, CORE_Y, 0);
        Part crystal = m.bone("crystal", heart, 0, CORE_Y, 0).rot(0, Math.PI / 4, 0);
        double[][] layers = {{1, -24, -19}, {3, -19, -14}, {5, -14, -9}, {7, -9, 0}, {5, 0, 5}, {3, 5, 9}, {1, 9, 13}};
        for (double[] l : layers) m.ab(crystal, "crystal", -l[0], l[1], -l[0], l[0], l[2], l[0]);
        // Adern aus Licht im Inneren (nur Leuchtschicht): flache Kreuze
        Part flare = m.bone("flare", heart, 0, CORE_Y, 0);
        m.ab(flare, "cflare", -16, CORE_Y, -16, 16, CORE_Y, 16);
        m.ab(flare, "cflare", -16, CORE_Y - 16, 0, 16, CORE_Y + 16, 0);
        m.ab(flare, "cflare", 0, CORE_Y - 16, -16, 0, CORE_Y + 16, 16);
        // vier Blütenblätter (Fleischkapsel), Gelenk am Kragen; öffnen sich im Herzschlag
        String[] pn = {"petal_n", "petal_e", "petal_s", "petal_w"};
        for (int i = 0; i < 4; i++) {
            Part p = m.bone(pn[i], root, 0, 12, 0).rot(0, i * Math.PI / 2, 0);
            Part lo = m.bone(pn[i] + "_lo", p, 0, 12, -8).rot(-0.32, 0, 0);
            m.ab(lo, "petal", -6, -4, -9, 6, 12, -7);
            m.ab(lo, "petal", -4, -8, -9, 4, -4, -7);
            Part hi = m.bone(pn[i] + "_hi", lo, 0, -8, -8).rot(0.55, 0, 0);
            m.ab(hi, "petal", -3, -16, -9, 3, -8, -7);
            m.ab(hi, "petal", -1, -19, -9, 1, -16, -7);
        }
        // Sehnen: vier schräg in den Boden, zwei nach oben (zur Magendecke)
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            Part t = m.bone("tendon" + i, root, 0, 18, 0).rot(0, a, 0);
            Part t1 = m.bone("tendon" + i + "_a", t, 0, 18, -8).rot(-0.38, 0, 0);
            m.ab(t1, "sinew", -1.5, 18, -8, 1.5, 21, 8);
            Part t2 = m.bone("tendon" + i + "_b", t1, 0, 19.5, 8).rot(-0.4, 0, 0);
            m.ab(t2, "sinew", -1, 18.5, 8, 1, 20.5, 20);
        }
        for (int i = 0; i < 2; i++) {
            Part t = m.bone("strand" + i, root, 0, -20, 0).rot(0, i * Math.PI + 0.4, 0);
            Part t1 = m.bone("strand" + i + "_a", t, 0, -20, -4).rot(0.42, 0, 0);
            m.ab(t1, "sinew", -1, -44, -5, 1, -20, -3);
            Part t2 = m.bone("strand" + i + "_b", t1, 0, -44, -4).rot(-0.2, 0, 0);
            m.ab(t2, "sinew", -1.5, -62, -5.5, 1.5, -44, -2.5);
        }
        // kreisende Splitter
        Part orbit = m.bone("orbit", root, 0, CORE_Y, 0);
        for (int i = 0; i < 3; i++) {
            double a = i * Math.PI * 2 / 3;
            double x = Math.round(Math.cos(a) * 17), z = Math.round(Math.sin(a) * 17), y = CORE_Y + (i - 1) * 5;
            Part s = m.bone("shard" + i, orbit, x, y, z).rot(0.3 * i, a, 0.4);
            m.ab(s, "crystal", x - 1, y - 4, z - 1, x + 1, y + 4, z + 1);
            m.ab(s, "crystal", x - 0.5, y - 6, z - 0.5, x + 0.5, y - 4, z + 0.5);
        }
        return m;
    }

    /** 0 = heil, 1 = Risse, 2 = zerborsten (Leben unter 60 % / 25 %). */
    static int CORE_STAGE;

    static void corePaint(Tex t) {
        String m = t.c.mat;
        int st = CORE_STAGE;
        switch (m) {
            case "crystal" -> {
                boolean shard = t.c.part.name.startsWith("shard");
                double r = Math.hypot(t.x, t.z) / 8, h = (t.y - CORE_Y) / 19;
                double k = clamp(1 - Math.hypot(r, h * 0.9), 0, 1);
                double facet = t.nx > 0 ? 1.08 : t.nx < 0 ? 0.86 : t.nz > 0 ? 0.95 : t.nz < 0 ? 1.0 : t.ny < 0 ? 1.1 : 0.8;
                int col = grad(k, new double[]{0, 0.35, 0.7, 1}, new int[]{0x7a2ab8, 0xe060d0, 0xffb8f0, 0xffffff});
                col = mixc(col, 0x8ae8ff, 0.3 * ramp(fbm(t.x * 0.3, t.y * 0.3, t.z * 0.3, 301), 0.5, 0.8));
                col = scale(col, facet);
                int glow = argb(mixc(0xff70d8, 0xffffff, k), 150 + 105 * k);
                // feine Sterne im Kristall
                if (hash((int) Math.floor(t.x * 2), (int) Math.floor(t.y * 2), (int) Math.floor(t.z * 2), 303) < 0.05) { col = 0xffffff; glow = argb(0xffffff, 255); }
                if (shard) { col = scale(col, 0.9); glow = argb(0xd8b0ff, 200); }
                // Risse: dunkle Sprünge, aus denen grelles Licht bricht
                if (st > 0 && !shard) {
                    double[] v = voronoi(t.x / 3.2 + 7, t.y / 3.2, t.z / 3.2, 307);
                    double e = v[1] - v[0];
                    double gate = st == 1 ? ramp(fbm(t.x * 0.15, t.y * 0.15, t.z * 0.15, 309), 0.45, 0.6) : 1;
                    double w = st == 1 ? 0.07 : 0.14;
                    if (e < w * gate) { col = 0x1a0628; glow = argb(0xffffff, 255); }
                    else if (e < w * 2 * gate) { col = mixc(col, 0x5a1a7a, 0.5); glow = argb(0xffd0f6, 230); }
                    if (st == 2) col = scale(col, 0.85);
                }
                t.s1 = argb(col, 255); t.g1 = glow;
            }
            case "cflare" -> {
                double a, b;
                if (t.ny != 0) { a = t.x; b = t.z; } else if (t.nz != 0) { a = t.x; b = t.y - CORE_Y; } else { a = t.z; b = t.y - CORE_Y; }
                double r = Math.hypot(a, b) / 16;
                if (r >= 1) return;
                double ang = Math.atan2(b, a);
                double spike = Math.pow(Math.abs(Math.cos(ang * 2)), 16) * (1 - r);
                double i = clamp(Math.pow(1 - r, 2.2) * 0.8 + spike, 0, 1);
                t.g1 = argb(scale(mixc(0xff58c8, 0xffffff, i), 0.4 + 0.6 * i), 230 * i);
            }
            default -> {
                // Fleisch (Kragen, Blätter) und Sehnen: dunkel violett-rot, glimmende Adern
                boolean sinew = m.equals("sinew");
                double n = fbm(t.x * 0.25, t.y * 0.25, t.z * 0.25, 311);
                int col = sinew ? mixc(0x5a1a3a, 0x9a4a66, n) : mixc(0x3a0a24, 0x6e1a40, n);
                if (sinew) {
                    // Faserstreifen längs
                    double along = t.c.d >= t.c.h && t.c.d >= t.c.w ? t.x + t.y : t.x + t.z;
                    if ((along * 1.5 - Math.floor(along * 1.5)) < 0.35) col = scale(col, 0.75);
                    if (t.fw > 0.85 || t.fv < 0.1) col = mixc(col, 0xd8a0b8, 0.3);
                }
                double[] v = voronoi(t.x / 4, t.y / 4, t.z / 4, 313);
                double e = v[1] - v[0];
                int glow = 0;
                if (e < 0.06) { col = mixc(col, 0xff5cc8, 0.5); glow = argb(0xff6ad0, 80 + 50 * st); }
                else if (e < 0.14) col = scale(col, 0.8);
                // Blätter: helle Innenseite, die das Licht des Kristalls einfängt
                if (m.equals("petal") && t.nz > 0) { col = mixc(col, 0xff9ad8, 0.35); glow = maxGlow(glow, argb(0xff8ad8, 70)); }
                if (st == 2 && hash(qs(t.x), qs(t.y), qs(t.z), 317) < 0.06) { col = 0x12040c; }
                t.s1 = argb(col, 255); t.g1 = glow;
            }
        }
    }

    // =====================================================================================
    public static void main(String[] args) throws Exception {
        String rootDir = ".";
        String preview = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--preview")) preview = args[++i]; else rootDir = args[i];
        }
        Path root = Path.of(rootDir);
        Path tex = root.resolve("src/main/resources/assets/hha_void/textures/entity");
        Path kt = root.resolve("src/main/kotlin/dev/henny/hha_void/client/boss");

        Model w = whale();
        w.pack();
        w.paint(GenWhale::whalePaint);
        w.png(w.s1, tex.resolve("void_boss.png").toFile());
        w.png(w.g1, tex.resolve("void_boss_glow.png").toFile());
        w.png(w.s3, tex.resolve("void_boss_p3.png").toFile());
        w.png(w.g3, tex.resolve("void_boss_p3_glow.png").toFile());
        Files.writeString(kt.resolve("WhaleModelData.kt"), w.kotlin("WhaleModelData",
            " * Oruun, der Leviathan: 1 Einheit = 0,3 Blöcke (Renderer skaliert x4,8), +Y unten, Blick nach -Z, links = +X.\n"
                + " * Ursprung = Körpermitte. Nur die Harpunen haben eine Ruhedrehung; alle Posen setzt [WhaleModel].\n"), StandardCharsets.UTF_8);

        Model s = starling();
        s.pack();
        s.paint(GenWhale::starlingPaint);
        s.png(s.s1, tex.resolve("void_debtor.png").toFile());
        s.png(s.g1, tex.resolve("void_debtor_glow.png").toFile());
        Files.writeString(kt.resolve("StarlingModelData.kt"), s.kotlin("StarlingModelData",
            " * Sternling (kleiner Sternrochen): 16 Einheiten = 1 Block, +Y unten, Blick nach -Z.\n"), StandardCharsets.UTF_8);

        // Sternenkern: drei Zustände (heil, Risse, zerborsten), je Haut + Leuchtschicht
        Model c = core();
        c.pack();
        String[] stage = {"leviathan_core", "leviathan_core_cracked", "leviathan_core_broken"};
        for (int i = 0; i < 3; i++) {
            CORE_STAGE = i;
            c.paint(GenWhale::corePaint);
            c.png(c.s1, tex.resolve(stage[i] + ".png").toFile());
            c.png(c.g1, tex.resolve(stage[i] + "_glow.png").toFile());
        }
        Files.writeString(kt.resolve("CoreModelData.kt"), c.kotlin("CoreModelData",
            " * Sternenkern im Bauch des Leviathans: 16 Einheiten = 1 Block, Boden bei y = 24, +Y unten.\n"
                + " * Kristallherz (heart/crystal), Fleischkapsel (petal_*), Sehnen (tendon*, strand*), kreisende Splitter (orbit).\n"), StandardCharsets.UTF_8);

        if (preview != null) {
            File dir = new File(preview);
            CORE_STAGE = 0;
            c.paint(GenWhale::corePaint);
            c.preview(new File(dir, "core.png"), null, 35, 20, 9, 900, 900, false, false);
            CORE_STAGE = 2;
            c.paint(GenWhale::corePaint);
            c.preview(new File(dir, "core_broken.png"), null, -35, 20, 9, 900, 900, false, false);
            Map<String, double[]> rest = new HashMap<>();
            // Wunde/Vorwölbung/Bauchglühen sind im Spiel nur zeitweise da
            double[] gone = {0, 0, 0, 0.001, 0.001, 0.001};
            for (String h : new String[]{"wound", "bulge", "gorge_c", "gorge_b"}) rest.put(h, gone);
            Map<String, double[]> open = new HashMap<>(rest);
            open.put("jaw", new double[]{0.9, 0, 0});
            open.put("cheek_l", new double[]{0.45, 0, 0});
            open.put("cheek_r", new double[]{0.45, 0, 0});
            open.put("head", new double[]{-0.22, 0, 0});
            open.put("fin_l", new double[]{0, -0.5, -0.3});
            open.put("fin_r", new double[]{0, 0.5, 0.3});
            open.put("blow_l", new double[]{0, 0, 1.1});
            open.put("blow_r", new double[]{0, 0, -1.1});
            Map<String, double[]> devour = new HashMap<>(open);
            devour.put("jaw", new double[]{1.75, 0, 0});
            devour.put("cheek_l", new double[]{0.85, 0, 0});
            devour.put("cheek_r", new double[]{0.85, 0, 0});
            devour.put("head", new double[]{-0.4, 0, 0});
            for (int i = 0; i < 6; i++) {
                devour.put("pj" + i, new double[]{0, 0, 0, 1, 1, 1, (-10 + 4 * i) * 0.3, 0, 0});
                devour.put("pn" + i, new double[]{0, 0, 0, 1, 1, 1, (-10 + 4 * i) * 0.3, 0, 0});
            }
            Map<String, double[]> hurt = new HashMap<>(open);
            hurt.remove("wound");
            hurt.put("flap_t", new double[]{0, 0, 1.9});
            hurt.put("flap_b", new double[]{0, 0, -1.7});
            hurt.put("flap_f", new double[]{0, -1.6, 0});
            hurt.put("flap_k", new double[]{0, 1.5, 0});
            hurt.remove("gorge_c");
            hurt.remove("gorge_b");
            w.preview(new File(dir, "whale_side.png"), rest, 90, 0, 6, 1500, 620, false, false);
            w.preview(new File(dir, "whale_side_boxes.png"), rest, 90, 0, 6, 1500, 620, false, true);
            w.preview(new File(dir, "whale_top_boxes.png"), rest, 90, 90, 6, 1500, 900, false, true);
            w.preview(new File(dir, "whale_side_p3.png"), hurt, -90, 0, 6, 1500, 620, true, false);
            w.preview(new File(dir, "whale_34.png"), open, 40, 18, 5, 1400, 900, false, false);
            w.preview(new File(dir, "whale_34_p3.png"), hurt, -40, 18, 5, 1400, 900, true, false);
            w.preview(new File(dir, "whale_front.png"), devour, 0, 5, 7, 1000, 1000, false, false);
            w.preview(new File(dir, "whale_devour.png"), devour, 55, 5, 5, 1400, 1000, false, false);
            w.preview(new File(dir, "whale_top.png"), rest, 90, 90, 6, 1500, 900, false, false);
            w.preview(new File(dir, "whale_below.png"), devour, 60, -50, 5, 1400, 1000, false, false);
            w.preview(new File(dir, "whale_head.png"), rest, 60, 8, 12, 1400, 900, false, false);
            w.preview(new File(dir, "whale_back.png"), rest, 150, 25, 8, 1400, 900, false, false);
            s.preview(new File(dir, "starling.png"), null, 35, 35, 22, 1000, 700, false, false);
        }
        System.out.println("fertig");
    }
}
