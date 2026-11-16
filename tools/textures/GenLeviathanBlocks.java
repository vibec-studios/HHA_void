import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Texturen für das Innere des Leviathans (Bauch-Phase) und den Sternenaufzug der Himmelsarena. 16×16 je Bild,
 * alle nahtlos kachelbar; bewegte Texturen als senkrechter Bildstreifen mit .mcmeta (Schleife ohne Sprung).
 * Deterministisch, nur JDK. Aufruf aus dem Repo-Wurzelverzeichnis:
 *   java tools/textures/GenLeviathanBlocks.java . [--preview <datei.png>]
 *
 *  - leviathan_flesh     feuchtes, dunkel violett-rotes Muskelfleisch, Fasern + Zellen, pulsiert im Herzschlag
 *  - leviathan_vein      leuchtende Ader im Fleisch: Sternenlicht fließt in Schüben hindurch (Block ist selbstleuchtend)
 *  - leviathan_rib       Elfenbein-Knochen, porig, Wachstumsringe, Sternenkruste aus Amethyst
 *  - leviathan_acid      Verdauungssäure: trübe Galle mit Lichtadern, aufsteigende Blasen, schillernder Schaum
 *  - leviathan_membrane  durchscheinend rosa, Kapillarnetz und dunkle Adern, schwaches Pochen
 *  - star_lift           senkrechte Lichtfäden und aufsteigende Funken (fast durchsichtig)
 */
public class GenLeviathanBlocks {

    static final int S = 16;
    static final double TAU = Math.PI * 2;

    interface Pixel { int at(int x, int y, int frame, int frames); }

    public static void main(String[] args) throws Exception {
        String root = ".";
        String preview = null;
        for (int i = 0; i < args.length; i++) if (args[i].equals("--preview")) preview = args[++i]; else root = args[i];
        File dir = new File(root, "src/main/resources/assets/hha_void/textures/block");
        dir.mkdirs();
        BufferedImage[] all = {
            write(dir, "leviathan_flesh", 8, 4, true, GenLeviathanBlocks::flesh),
            write(dir, "leviathan_vein", 16, 2, true, GenLeviathanBlocks::vein),
            write(dir, "leviathan_rib", 1, 0, false, GenLeviathanBlocks::rib),
            write(dir, "leviathan_acid", 16, 3, true, GenLeviathanBlocks::acid),
            write(dir, "leviathan_membrane", 8, 4, true, GenLeviathanBlocks::membrane),
            write(dir, "star_lift", 16, 1, false, GenLeviathanBlocks::lift),
        };
        if (preview != null) preview(new File(preview), all);
        System.out.println("ok -> " + dir);
    }

    static BufferedImage write(File dir, String name, int frames, int frametime, boolean interpolate, Pixel p) throws Exception {
        BufferedImage img = new BufferedImage(S, S * frames, BufferedImage.TYPE_INT_ARGB);
        for (int f = 0; f < frames; f++) for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) img.setRGB(x, f * S + y, p.at(x, y, f, frames));
        ImageIO.write(img, "png", new File(dir, name + ".png"));
        File meta = new File(dir, name + ".png.mcmeta");
        if (frames > 1) {
            Files.writeString(meta.toPath(), "{\n  \"animation\": {\n    \"frametime\": " + frametime + ",\n    \"interpolate\": " + interpolate + "\n  }\n}\n", StandardCharsets.UTF_8);
        } else if (meta.exists()) meta.delete();
        return img;
    }

    /** Vorschau: je Textur 3×3 Kacheln (Bild 0) und daneben die ersten Bilder der Animation, vergrößert, auf Grau. */
    static void preview(File out, BufferedImage[] all) throws Exception {
        int z = 6, cols = 3 * S * z + 8 + 8 * (S * z / 2 + 4);
        BufferedImage o = new BufferedImage(cols, all.length * (3 * S * z + 10), BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < o.getWidth(); i++) for (int j = 0; j < o.getHeight(); j++) o.setRGB(i, j, ((i / 12 + j / 12) & 1) == 0 ? 0x3a3a44 : 0x2c2c34);
        for (int t = 0; t < all.length; t++) {
            BufferedImage im = all[t];
            int frames = im.getHeight() / S, oy = t * (3 * S * z + 10);
            for (int ty = 0; ty < 3; ty++) for (int tx = 0; tx < 3; tx++) for (int y = 0; y < S * z; y++) for (int x = 0; x < S * z; x++)
                o.setRGB(tx * S * z + x, oy + ty * S * z + y, over(o.getRGB(tx * S * z + x, oy + ty * S * z + y), im.getRGB(x / z, y / z)));
            for (int f = 0; f < Math.min(frames, 8); f++) for (int y = 0; y < S * z / 2; y++) for (int x = 0; x < S * z / 2; x++) {
                int px = 3 * S * z + 8 + f * (S * z / 2 + 4) + x, py = oy + y;
                o.setRGB(px, py, over(o.getRGB(px, py), im.getRGB(x * 2 / z, f * S + y * 2 / z)));
            }
        }
        ImageIO.write(o, "png", out);
    }

    static int over(int bg, int fg) {
        double a = (fg >>> 24) / 255.0;
        return mix(bg, fg, a);
    }

    // =====================================================================================
    // Werkzeug: kachelbares Rauschen (Gitter läuft periodisch über 16 px und über die Animation)

    static double hash(int a, int b, int c, int s) {
        int h = a * 374761393 + b * 668265263 + c * 1440662683 + s * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 15;
        return (h & 0xffffff) / (double) 0x1000000;
    }

    /** Wertrauschen, periodisch: [cell] px je Gitterzelle (teilt 16), [tp] Gitterzellen über die ganze Animation. */
    static double noise(double x, double y, double t, int cell, int tp, int seed) {
        int px = S / cell;
        double fx = x / cell, fy = y / cell, ft = t * tp;
        int xi = (int) Math.floor(fx), yi = (int) Math.floor(fy), ti = (int) Math.floor(ft);
        double ax = smooth(fx - xi), ay = smooth(fy - yi), at = smooth(ft - ti);
        double r = 0;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = (i >> 1) & 1, dt = (i >> 2) & 1;
            double h = hash(Math.floorMod(xi + dx, px), Math.floorMod(yi + dy, px), tp > 0 ? Math.floorMod(ti + dt, tp) : 0, seed);
            r += h * (dx == 1 ? ax : 1 - ax) * (dy == 1 ? ay : 1 - ay) * (dt == 1 ? at : 1 - at);
        }
        return r;
    }

    static double fbm(double x, double y, double t, int tp, int seed) {
        return (noise(x, y, t, 8, tp, seed) * 0.5 + noise(x, y, t, 4, tp * 2, seed + 7) * 0.3 + noise(x, y, t, 2, tp * 2, seed + 13) * 0.2);
    }

    static double smooth(double k) { return k * k * (3 - 2 * k); }
    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }
    static double ramp(double x, double a, double b) { return smooth(clamp((x - a) / (b - a), 0, 1)); }

    /** Kürzester Abstand auf der 16er-Kachel. */
    static double wrap(double d) { d = d - S * Math.floor(d / S); return d > S / 2.0 ? d - S : d; }
    static double wdist(double x, double y, double cx, double cy) { return Math.hypot(wrap(x - cx), wrap(y - cy)); }

    /** Zellen (Voronoi) auf der Kachel: {Abstand nächste, zweitnächste, Zellwert}. */
    static double[] cells(double x, double y, int n, int seed) {
        double c = (double) S / n, f1 = 99, f2 = 99, id = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double cx = (i + 0.15 + 0.7 * hash(i, j, 0, seed)) * c, cy = (j + 0.15 + 0.7 * hash(i, j, 1, seed)) * c;
            double d = wdist(x, y, cx, cy);
            if (d < f1) { f2 = f1; f1 = d; id = hash(i, j, 2, seed); } else if (d < f2) f2 = d;
        }
        return new double[]{f1, f2, id};
    }

    static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double best = 99;
        // auf der Kachel: auch die Nachbarkopien prüfen
        for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) {
            double qx = px + ox * S, qy = py + oy * S, dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy;
            double k = l < 1e-9 ? 0 : clamp(((qx - ax) * dx + (qy - ay) * dy) / l, 0, 1);
            best = Math.min(best, Math.hypot(qx - ax - dx * k, qy - ay - dy * k));
        }
        return best;
    }

    static int rgb(int r, int g, int b) { return (int) clamp(r, 0, 255) << 16 | (int) clamp(g, 0, 255) << 8 | (int) clamp(b, 0, 255); }
    static int argb(int rgb, double a) { return ((int) Math.round(clamp(a, 0, 255)) << 24) | (rgb & 0xffffff); }
    static int mix(int a, int b, double t) {
        t = clamp(t, 0, 1);
        return rgb((int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t),
            (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
            (int) Math.round((a & 255) * (1 - t) + (b & 255) * t));
    }
    static int scale(int c, double k) { return rgb((int) (((c >> 16) & 255) * k), (int) (((c >> 8) & 255) * k), (int) ((c & 255) * k)); }
    static int grad(double t, double[] ts, int[] cs) {
        if (t <= ts[0]) return cs[0];
        for (int i = 1; i < ts.length; i++) if (t <= ts[i]) return mix(cs[i - 1], cs[i], (t - ts[i - 1]) / (ts[i] - ts[i - 1]));
        return cs[cs.length - 1];
    }

    /** Herzschlag über die Schleife: Doppelschlag, 0..1. */
    static double beat(double t) {
        double a = Math.max(0, Math.sin(TAU * t)), b = Math.max(0, Math.sin(TAU * t - 1.1));
        return clamp(Math.pow(a, 3) + 0.55 * Math.pow(b, 3), 0, 1);
    }

    // =====================================================================================
    // Fleisch

    static final double[] FT = {0, 0.3, 0.55, 0.8, 1};
    static final int[] FC = {0x1c0414, 0x3e0a26, 0x6a1638, 0x93284e, 0xc04a6c};

    /** Muskel: Faserbündel schräg, darüber feuchte Zellen; [t] = Zeit 0..1 der Schleife. */
    static int fleshColor(int x, int y, double t, double swell) {
        double warp = (fbm(x, y, 0, 0, 3) - 0.5) * 5;
        // Fasern: Richtung (1, 3)/16 kachelt
        double fib = 0.5 + 0.5 * Math.sin(TAU * (x + 2 * y) / 16.0 + warp * 1.4 + swell * 0.6);
        double[] c = cells(x + 0.5, y + 0.5, 3, 11);
        double edge = c[1] - c[0];
        double n = fbm(x, y, t, 1, 5);
        double v = 0.2 + 0.22 * fib + 0.45 * (n - 0.35) + 0.22 * c[2] + 0.25 * ramp(c[0], 2.6, 0.6);
        v *= 0.86 + 0.24 * swell;
        if (edge < 1.1) v *= 0.45 + 0.55 * edge / 1.1; // Furchen zwischen den Bündeln
        int col = grad(clamp(v, 0, 1), FT, FC);
        // violetter Schimmer (Sternenlicht im Gewebe)
        col = mix(col, 0x4a1a6a, 0.25 * ramp(fbm(x, y, 0, 0, 9), 0.55, 0.75));
        // feuchter Glanz auf den Wülsten
        double wet = fib * ramp(n, 0.55, 0.75) * (edge > 1.2 ? 1 : 0);
        if (wet > 0.55) col = mix(col, 0xffc2dc, 0.35 + 0.35 * swell);
        return col;
    }

    static int flesh(int x, int y, int f, int frames) {
        double t = (double) f / frames;
        return argb(fleshColor(x, y, t, beat(t)), 255);
    }

    // =====================================================================================
    // Ader

    /** Abstand zur Ader: Hauptstrang quer (kachelt waagerecht), ein Ast nach oben, der unten wieder hereinkommt. */
    static double[] veinDist(double x, double y) {
        double best = 99, along = 0;
        for (int i = 0; i < 32; i++) {
            double ax = i * 0.5, ay = 8 + 2.2 * Math.sin(TAU * ax / 16.0), bx = ax + 0.5, by = 8 + 2.2 * Math.sin(TAU * bx / 16.0);
            double d = segDist(x, y, ax, ay, bx, by);
            if (d < best) { best = d; along = ax; }
        }
        // Ast: von (3, ~10,5) hoch zur Oberkante bei x = 6,5; von der Unterkante bei x = 6,5 hinab zum Strang bei x = 11
        double ya = 8 + 2.2 * Math.sin(TAU * 3 / 16.0), yb = 8 + 2.2 * Math.sin(TAU * 11 / 16.0);
        double b1 = segDist(x, y, 3, ya, 5, 3), b2 = segDist(x, y, 5, 3, 6.5, 0), b3 = segDist(x, y, 6.5, 16, 9, yb + 2), b4 = segDist(x, y, 9, yb + 2, 11, yb);
        double br = Math.min(Math.min(b1, b2), Math.min(b3, b4));
        return new double[]{best, along, br};
    }

    static int vein(int x, int y, int f, int frames) {
        double t = (double) f / frames;
        double px = x + 0.5, py = y + 0.5;
        int col = scale(fleshColor(x, y, t, 0.3 * beat(t)), 0.62);
        double[] v = veinDist(px, py);
        double main = v[0], br = v[2];
        // Lichtschübe wandern den Strang entlang (eine Kachel je Schleife) und in den Ast hinein
        double flow = Math.pow(0.5 + 0.5 * Math.cos(TAU * (px / 16.0 - t)), 6) + 0.6 * Math.pow(0.5 + 0.5 * Math.cos(TAU * (px / 16.0 - t) * 2 + 1.3), 8);
        double flowB = Math.pow(0.5 + 0.5 * Math.cos(TAU * (-py / 16.0 - t + 0.3)), 6);
        flow = clamp(flow, 0, 1.2);
        // Wand der Ader: dunkler Rand, magenta Glut, heller Kern
        if (main < 2.4) {
            double k = 1 - main / 2.4;
            int wall = mix(0x5a0c4a, 0xc0309c, k);
            col = mix(col, wall, ramp(k, 0, 0.35));
            if (main < 1.25) col = mix(0xe048c0, mix(0xffb0ec, 0xffffff, flow * 0.7), clamp((1.25 - main) / 0.7, 0, 1) * (0.55 + 0.45 * flow));
        } else if (main < 3.6) col = scale(col, 0.75 + 0.25 * (main - 2.4) / 1.2); // Schatten neben der Ader
        if (br < 1.1) {
            double k = 1 - br / 1.1;
            col = mix(col, mix(0x9a2a8a, mix(0x7ad8ff, 0xffffff, flowB * 0.5), 0.4 + 0.6 * flowB), ramp(k, 0, 0.5));
        }
        // feine Kapillaren mit cyanem Sternenstaub
        double[] c = cells(px, py, 4, 31);
        if (c[1] - c[0] < 0.35 && main > 2.4 && br > 1.1) col = mix(col, 0xa83a8c, 0.45);
        double spark = hash(x, y, Math.floorMod((int) Math.floor(t * frames + x * 3), frames), 41);
        if (main < 1.0 && spark < 0.05) col = 0xcff6ff;
        return argb(col, 255);
    }

    // =====================================================================================
    // Rippe

    static int rib(int x, int y, int f, int frames) {
        double px = x + 0.5, py = y + 0.5;
        double n = fbm(x, y, 0, 0, 51);
        // Wachstumsringe: leicht gewellte Querbänder
        double ring = 0.5 + 0.5 * Math.sin(TAU * (py + 1.6 * Math.sin(TAU * px / 16.0)) / 16.0 * 3);
        int col = grad(clamp(0.35 + 0.45 * n + 0.2 * ring, 0, 1), new double[]{0, 0.4, 0.75, 1}, new int[]{0x8c7c66, 0xc2b296, 0xe6dac0, 0xf6eedc});
        // Poren
        if (hash(x, y, 0, 53) < 0.07) col = scale(col, 0.7);
        // feiner Riss
        double crack = Math.min(segDist(px, py, 2, 4, 6, 7), segDist(px, py, 6, 7, 7.5, 11));
        if (crack < 0.5) col = mix(col, 0x4a3a34, 0.7);
        else if (crack < 1.0) col = scale(col, 0.88);
        // Sternenkruste aus Amethyst, eine Ecke (läuft über die Kante in die Nachbarkachel)
        double crust = 1 - wdist(px, py, 12.5, 12.5) / 7.5 + (noise(x, y, 0, 2, 0, 57) - 0.5) * 0.45 + (noise(x, y, 0, 4, 0, 58) - 0.5) * 0.35;
        if (crust > 0.25) {
            double[] c = cells(px, py, 4, 59);
            double e = c[1] - c[0];
            int cc = mix(0x4a2a8a, 0xb894ff, c[2]);
            if (c[0] < 0.6) cc = mix(cc, 0xf0e4ff, 0.6);
            if (e < 0.3) cc = 0x24123e;
            col = mix(col, cc, ramp(crust, 0.25, 0.4));
        }
        // dunkle Fuge zum Fleisch, wo der Knochen ansetzt
        return argb(col, 255);
    }

    // =====================================================================================
    // Säure

    static int acid(int x, int y, int f, int frames) {
        double t = (double) f / frames;
        double px = x + 0.5, py = y + 0.5;
        // trübe Tiefe: Schlieren, die langsam kreisen
        double sw = fbm(px + 3 * Math.sin(TAU * t), py + 3 * Math.cos(TAU * t), t, 1, 61);
        double depth = clamp(ramp(sw, 0.3, 0.75) * 0.85 + 0.1 * noise(px, py, t, 2, 4, 63), 0, 1);
        int col = grad(depth, new double[]{0, 0.35, 0.6, 0.85, 1}, new int[]{0x0e2410, 0x22501a, 0x4e8a1c, 0x8cc626, 0xc8f04a});
        // Gallenfarbe kippt stellenweise ins Türkis, in den Tiefen glimmt Sternenviolett
        col = mix(col, 0x1c8a78, 0.35 * ramp(fbm(x, y, t, 1, 67), 0.5, 0.75));
        col = mix(col, 0x5a2a8c, 0.3 * ramp(1 - depth, 0.55, 0.85));
        // Lichtadern (Kaustik): helle Netzlinien, die wandern
        double n1 = noise(px, py, t, 4, 2, 71), n2 = noise(px + 5, py + 9, t, 8, 1, 73);
        double caus = 1 - Math.abs(Math.sin((n1 * 1.4 + n2) * Math.PI * 2.5));
        caus = Math.pow(caus, 6);
        col = mix(col, 0xe6ff9a, 0.75 * caus);
        double a = 196 + 40 * caus;
        // Blasen: wachsen, platzen (heller Ring), Schaumpunkte bleiben kurz
        for (int i = 0; i < 9; i++) {
            double bx = 16 * hash(i, 0, 0, 81), by = 16 * hash(i, 1, 0, 81), t0 = hash(i, 2, 0, 81), life = 0.35 + 0.2 * hash(i, 3, 0, 81);
            double age = t - t0;
            age -= Math.floor(age);
            if (age > life) continue;
            double k = age / life;
            // steigt ein Stück (auf der Seitenfläche sieht man sie aufsteigen)
            double cy = by - 3 * k;
            double r = 0.5 + (0.6 + 0.6 * hash(i, 4, 0, 81)) * k;
            double d = wdist(px, py, bx, cy);
            if (k < 0.85) {
                if (d < r - 0.55) { col = mix(col, 0x7ec42a, 0.35); a = Math.max(a, 170); }
                else if (d < r + 0.35) { col = mix(col, 0xf4ffc0, 0.8); a = 245; }
                if (Math.abs(wrap(px - (bx - r * 0.35))) < 0.6 && Math.abs(wrap(py - (cy - r * 0.35))) < 0.6) col = 0xffffff;
            } else if (Math.abs(d - r - 0.6) < 0.5) { col = mix(col, 0xffffe0, 0.9); a = 250; }
        }
        // Schaum am Rand der Blasen und frische Spritzer
        if (hash(x, y, f, 87) < 0.025) { col = mix(col, 0xf0ffd0, 0.7); a = 240; }
        return argb(col, a);
    }

    // =====================================================================================
    // Membran

    static int membrane(int x, int y, int f, int frames) {
        double t = (double) f / frames;
        double px = x + 0.5, py = y + 0.5, b = beat(t);
        double n = fbm(x, y, t, 1, 91);
        int col = mix(0xe878b4, 0xffb4dc, n);
        double a = 92 + 60 * n + 26 * b;
        // Kapillarnetz
        double[] c = cells(px, py, 3, 93);
        double e = c[1] - c[0];
        if (e < 0.45) { col = mix(col, 0xa0204c, 0.75); a = 190 + 30 * b; }
        else if (e < 1.0) { col = mix(col, 0xd04880, 0.35); a += 30; }
        // große dunkle Ader quer
        double vd = 99;
        for (int i = 0; i < 32; i++) {
            double ax = i * 0.5, ay = 5 + 2.5 * Math.sin(TAU * (ax / 16.0 + 0.2)) + 1.2 * Math.sin(TAU * ax / 8.0), bx = ax + 0.5, by = 5 + 2.5 * Math.sin(TAU * (bx / 16.0 + 0.2)) + 1.2 * Math.sin(TAU * bx / 8.0);
            vd = Math.min(vd, segDist(px, py, ax, ay, bx, by));
        }
        if (vd < 1.3) { col = mix(0x4a0626, 0x7a1440, vd / 1.3); a = 235; }
        else if (vd < 2.0) { col = mix(col, 0xff9ad0, 0.3 + 0.4 * b); a += 20; }
        // Licht von außen schimmert durch: helle Flecken, die mit dem Puls atmen
        double light = ramp(noise(px, py, t, 8, 1, 97), 0.62, 0.85);
        col = mix(col, 0xfff0f8, 0.45 * light * (0.6 + 0.4 * b));
        return argb(col, a);
    }

    // =====================================================================================
    // Sternenaufzug

    static int lift(int x, int y, int f, int frames) {
        double t = (double) f / frames;
        double a = 18, k = 0;
        int col = 0x8a6aff;
        // Lichtfäden: Helligkeit wandert nach oben (1 px je Bild)
        int[] xs = {3, 6, 9, 12};
        int[] cs = {0xb89cff, 0x8ae8ff, 0xd8b8ff, 0x9af0ff};
        for (int i = 0; i < xs.length; i++) {
            if (x != xs[i]) continue;
            double ph = (y + f) / 16.0 + 0.27 * i;
            double s = Math.pow(0.5 + 0.5 * Math.cos(TAU * ph), 3);
            k = 0.35 + 0.65 * s;
            col = cs[i];
            a = 70 + 150 * s;
        }
        // Funken steigen auf: je 16 Bilder einmal ganz hinauf
        for (int i = 0; i < 5; i++) {
            int sx = (int) (16 * hash(i, 0, 0, 101)), sy = Math.floorMod((int) (16 * hash(i, 1, 0, 101)) - f, 16);
            if (x == sx && y == sy) { col = 0xffffff; a = 255; }
            if (x == sx && y == Math.floorMod(sy + 1, 16)) { col = mix(col, 0xe8dcff, 0.8); a = Math.max(a, 150); }
            if (x == sx && y == Math.floorMod(sy + 2, 16)) { col = mix(col, 0xb89cff, 0.6); a = Math.max(a, 80); }
        }
        if (k == 0 && a < 30) a = 14 + 10 * Math.sin(TAU * (y / 16.0 + t));
        return argb(col, a);
    }
}
