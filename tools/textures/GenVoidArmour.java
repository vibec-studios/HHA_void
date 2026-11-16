import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * Void-Rüstung = Vanilla-Netherit-Rüstung im END-PORTAL-LOOK + AMETHYST-Rüstungs-Trims.
 * Metall: die Netherit-Textur wird Pixel für Pixel nach ihrer Helligkeit auf eine tief schwarzblaue Rampe
 * umgefärbt (Platten, Kanten und Schattierung bleiben erhalten) und mit Sternpunkten besprenkelt - meist
 * türkis/cyan, vereinzelt violett, wie die Oberfläche eines End-Portals (siehe portal()).
 * Trims: exakt so zusammengesetzt wie Minecraft Trims einfärbt (Paletten-Permutation des Graustufen-Musters).
 * Die Amethyst-Palette ist nur leicht aufgehellt ("leuchtend"), Farbton unverändert - klar als Amethyst-Trim
 * erkennbar.
 * Gleiches Verfahren wie die Blood-Rüstung im Schwester-Addon (dort GenTrimArmour.java).
 *
 * Muster je Teil (Vorgabe von Mats):
 *   - Helm:         vex
 *   - Brustplatte:  eye
 *   - Hose:         tide
 *   - Schuhe:       flow
 * Helm, Brustplatte und Schuhe teilen sich EINE Ebenen-Textur (humanoid), deshalb wird das Muster je
 * UV-Bereich gewählt: Kopf/Hut -> HELMET_TRIM, Körper/Arme -> CHEST_TRIM, Beine (Schuhe) -> BOOTS_TRIM.
 * Die Hosen-Ebene (humanoid_leggings) ist komplett LEGS_TRIM.
 *
 * Schreibt:
 *   textures/entity/equipment/humanoid/void.png + void_glow.png
 *   textures/entity/equipment/humanoid_leggings/void.png + void_glow.png
 *   textures/item/void_{helmet,chestplate,leggings,boots}.png (+ .mcmeta, 4 Frames: Trim-Glühpuls + Sternfunkeln)
 * Die *_glow-Ebenen enthalten die Trim-Pixel und - halbtransparent, also deutlich schwächer - die helleren
 * Sternpunkte; der Client zeichnet sie lichtunabhängig und pulsierend (EquipmentRendererMixin, equipment/void.json).
 *
 * Aufruf (im Projektordner):
 *   java tools/textures/GenVoidArmour.java .                      Texturen schreiben
 *   java tools/textures/GenVoidArmour.java . --preview out.png    nur Vorschau (schreibt keine Texturen)
 * Optional letztes Argument: Pfad zur minecraft-client.jar
 * (Standard ~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar).
 */
public class GenVoidArmour {

    static final String HELMET_TRIM = "vex";
    static final String CHEST_TRIM = "eye";
    static final String LEGS_TRIM = "tide";
    static final String BOOTS_TRIM = "flow";

    /** Kombinationen für --preview: {Helm, Brust, Hose, Schuhe}; die erste ist die gewählte */
    static final String[][] CANDIDATES = {
        {HELMET_TRIM, CHEST_TRIM, LEGS_TRIM, BOOTS_TRIM},
    };

    static ZipFile jar;
    static Path tex;

    static BufferedImage vanilla(String path) throws Exception {
        var e = jar.getEntry("assets/minecraft/textures/" + path);
        if (e == null) throw new IllegalStateException("fehlt in der jar: " + path);
        try (InputStream in = jar.getInputStream(e)) {
            return raw(ImageIO.read(in));
        }
    }

    /**
     * Rohe Pixelwerte, so wie Minecrafts NativeImage sie liest. Javas getRGB()/drawImage() wenden auf
     * Graustufen-PNGs eine Linear->sRGB-Umrechnung an, die den exakten Paletten-Abgleich zerstören würde.
     */
    static BufferedImage raw(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var r = src.getRaster();
        int bands = r.getNumBands();
        boolean indexed = src.getColorModel() instanceof java.awt.image.IndexColorModel;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int argb;
            if (indexed) argb = src.getRGB(x, y);
            else if (bands == 1) { int g = r.getSample(x, y, 0); argb = 0xFF000000 | (g << 16) | (g << 8) | g; }
            else if (bands == 2) { int g = r.getSample(x, y, 0), a = r.getSample(x, y, 1); argb = (a << 24) | (g << 16) | (g << 8) | g; }
            else if (bands == 3) argb = 0xFF000000 | (r.getSample(x, y, 0) << 16) | (r.getSample(x, y, 1) << 8) | r.getSample(x, y, 2);
            else argb = (r.getSample(x, y, 3) << 24) | (r.getSample(x, y, 0) << 16) | (r.getSample(x, y, 1) << 8) | r.getSample(x, y, 2);
            out.setRGB(x, y, argb);
        }
        return out;
    }

    /**
     * Void-Palette = Vanilla-Amethyst, nur etwas heller und satter ("leuchtend"), Farbton unverändert.
     * [boost] hellt weiter auf (2. Icon-Frame).
     */
    static int[] palette(BufferedImage amethyst, double boost) {
        int n = amethyst.getWidth();
        int[] p = new int[n];
        float bMax = 0;
        float[][] hsb = new float[n][];
        for (int i = 0; i < n; i++) {
            int c = amethyst.getRGB(i, 0);
            hsb[i] = Color.RGBtoHSB((c >> 16) & 255, (c >> 8) & 255, c & 255, null);
            bMax = Math.max(bMax, hsb[i][2]);
        }
        for (int i = 0; i < n; i++) {
            float light = hsb[i][2] / bMax;                       // 0 dunkel .. 1 hellste Stufe
            float hue = hsb[i][0];
            float s = (float) Math.min(1.0, hsb[i][1] * (1.16 - 0.06 * light) * (0.96 + 0.04 * boost));
            float b = (float) Math.min(1.0, hsb[i][2] * 1.10 * boost);
            p[i] = Color.HSBtoRGB(hue, s, b) & 0xFFFFFF;
        }
        return p;
    }

    // ------------------------------------------------------------------ End-Portal-Metall

    /** Schwarzblau-Rampe des Metalls: dunkelste Fuge .. hellste Plattenkante */
    static final int[] PORTAL = {0x03050A, 0x060A12, 0x09101B, 0x0D1726, 0x121F33, 0x192B44, 0x23395A, 0x30496E};
    /** Sternfarben [Art][Helligkeit]: türkis, cyanblau, violett - je schwach / mittel / hell */
    static final int[][] STARS = {
        {0x1A5A66, 0x2BA596, 0x74F0DA},
        {0x1C4C80, 0x2E7CC2, 0x86CCF6},
        {0x41347C, 0x6E52C8, 0xB9A2FF},
    };
    /** Anteil der Metall-Pixel, die einen Stern tragen (Rüstungsebenen / Icons) */
    static final double STAR_DENSITY = 0.115, STAR_DENSITY_ICON = 0.16;
    static final int ICON_FRAMES = 4;

    static double hash(int a, int b, int c) {
        int h = a * 374761393 + b * 668265263 + c * 2147483647 + 0x9E3779B9;
        h = (h ^ (h >>> 13)) * 1274126177; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
    static double lum(int c) { return (((c >> 16) & 255) * 0.299 + ((c >> 8) & 255) * 0.587 + (c & 255) * 0.114) / 255.0; }
    static int ramp(int[] pal, double t) {
        double f = Math.max(0, Math.min(1, t)) * (pal.length - 1);
        int i = Math.min(pal.length - 2, (int) f);
        double k = f - i;
        int a = pal[i], b = pal[i + 1];
        int r = (int) Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = (int) Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** umgefärbtes Metall + Sternkarte: star[y][x] = 0 (kein Stern) oder 1 + Art*8 + Phase (Phase 0..7 fürs Funkeln) */
    static class Portal { BufferedImage img; int[][] star; double[][] tone; }

    /**
     * Netherit -> End-Portal-Metall. Die Helligkeit jedes Pixels (relativ zum dunkelsten/hellsten der Textur)
     * wählt die Stufe der Schwarzblau-Rampe: Fugen bleiben fast schwarz, Plattenkanten werden stahlblau - die
     * Rüstung bleibt als Rüstung lesbar. Sterne sitzen nur auf den Flächen (mittlere Helligkeit), nie auf den
     * hellen Kanten oder in den Fugen, und nie zwei direkt nebeneinander.
     * [inset]: Sterne halten Abstand zum Rand der Figur (Icons: der Umriss bleibt sauber).
     */
    static Portal portal(BufferedImage base, int seed, double density, boolean inset) {
        int w = base.getWidth(), h = base.getHeight();
        double lo = 1, hi = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = base.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            lo = Math.min(lo, lum(c)); hi = Math.max(hi, lum(c));
        }
        Portal p = new Portal();
        p.img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        p.star = new int[h][w]; p.tone = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = base.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            double t = Math.pow((lum(c) - lo) / Math.max(1e-6, hi - lo), 1.1);
            p.tone[y][x] = t;
            p.img.setRGB(x, y, (c & 0xFF000000) | ramp(PORTAL, t));
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if ((base.getRGB(x, y) >>> 24) == 0) continue;
            double t = p.tone[y][x];
            if (t < 0.10 || t > 0.74 || hash(x, y, seed) >= density) continue;
            if ((x > 0 && p.star[y][x - 1] != 0) || (y > 0 && p.star[y - 1][x] != 0)) continue;
            if (inset) {
                boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1;
                for (int d = 0; d < 4 && !edge; d++) edge = (base.getRGB(x + (d == 0 ? 1 : d == 1 ? -1 : 0), y + (d == 2 ? 1 : d == 3 ? -1 : 0)) >>> 24) == 0;
                if (edge) continue;
            }
            double k = hash(x, y, seed + 7);
            int kind = k < 0.60 ? 0 : k < 0.80 ? 1 : 2;
            p.star[y][x] = 1 + kind * 8 + (int) (hash(x, y, seed + 13) * 8);
        }
        return p;
    }
    /** Helligkeitsstufe (0 schwach, 1 mittel, 2 hell) eines Sterns im Frame [frame]; die Ebenen-Texturen nutzen Frame 0 */
    static int starLevel(int star, int frame) {
        int phase = ((star - 1) % 8 + 2 * frame) % 8;                 // Frame 0: 50 % schwach, 37 % mittel, 13 % hell
        return phase == 7 ? 2 : phase >= 4 ? 1 : 0;
    }
    /** malt die Sterne in das Metall */
    static void stars(Portal p, int frame) {
        for (int y = 0; y < p.star.length; y++) for (int x = 0; x < p.star[y].length; x++) {
            int s = p.star[y][x];
            if (s == 0) continue;
            p.img.setRGB(x, y, (p.img.getRGB(x, y) & 0xFF000000) | STARS[(s - 1) / 8][starLevel(s, frame)]);
        }
    }
    /**
     * Sterne in die Glow-Ebene: nur mittlere und helle, halbtransparent (die Ebene wird mit normalem
     * Alpha-Blending bei voller Helligkeit gezeichnet) - nachts ein leises Funkeln, die Trims bleiben das
     * Hauptleuchten. [trimmed] = fertige Ebene mit Trims: Sterne, die unter einem Trim liegen, entfallen.
     */
    static void starGlow(Portal p, BufferedImage glow, BufferedImage trimmed) {
        for (int y = 0; y < p.star.length; y++) for (int x = 0; x < p.star[y].length; x++) {
            int s = p.star[y][x];
            if (s == 0 || (glow.getRGB(x, y) >>> 24) != 0) continue;
            int level = starLevel(s, 0);
            if (level == 0 || (trimmed.getRGB(x, y) & 0xFFFFFF) != STARS[(s - 1) / 8][level]) continue;
            glow.setRGB(x, y, ((level == 2 ? 215 : 110) << 24) | STARS[(s - 1) / 8][level]);
        }
    }

    /** Paletten-Permutation: jedes Pixel, dessen RGB key[i] entspricht, wird pal[i] (Alpha bleibt). */
    static BufferedImage recolor(BufferedImage pattern, int[] key, int[] pal) {
        BufferedImage out = new BufferedImage(pattern.getWidth(), pattern.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pattern.getHeight(); y++) for (int x = 0; x < pattern.getWidth(); x++) {
            int c = pattern.getRGB(x, y);
            int a = (c >>> 24);
            if (a == 0) continue;
            int rgb = c & 0xFFFFFF;
            int mapped = rgb;
            for (int i = 0; i < key.length; i++) if (key[i] == rgb) { mapped = pal[i]; break; }
            out.setRGB(x, y, (a << 24) | mapped);
        }
        return out;
    }

    static int over(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 0) return dst;
        if (sa == 255) return src;
        int da = dst >>> 24;
        double a = sa / 255.0, b = da / 255.0 * (1 - a);
        double oa = a + b;
        int r = (int) Math.round((((src >> 16) & 255) * a + ((dst >> 16) & 255) * b) / oa);
        int g = (int) Math.round((((src >> 8) & 255) * a + ((dst >> 8) & 255) * b) / oa);
        int bl = (int) Math.round(((src & 255) * a + (dst & 255) * b) / oa);
        return ((int) Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | bl;
    }

    /** base + trim, aber nur innerhalb des UV-Rechtecks. */
    static void overlay(BufferedImage base, BufferedImage trim, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) base.setRGB(x, y, over(base.getRGB(x, y), trim.getRGB(x, y)));
    }

    /**
     * Glüh-Fassung eines eingefärbten Trims. Die Ebene wird mit RenderLayers.eyes gezeichnet: volle
     * Helligkeit ohne Weltlicht, normales Alpha-Blending - sie ERSETZT also die Trim-Pixel der ersten
     * Ebene (mal Puls-Tönung aus dem Mixin). Deshalb: dunkelste Stufen weglassen (dort bleibt der normal
     * beleuchtete Trim sichtbar, das gibt den Linien Tiefe), sonst Helligkeit hochziehen; der Verlauf
     * bleibt über Farbton/Sättigung erhalten (Blauviolett -> Violett -> pink-weißer Kern).
     */
    static BufferedImage glow(BufferedImage trim) {
        BufferedImage out = new BufferedImage(trim.getWidth(), trim.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < trim.getHeight(); y++) for (int x = 0; x < trim.getWidth(); x++) {
            int c = trim.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            float[] hsb = Color.RGBtoHSB((c >> 16) & 255, (c >> 8) & 255, c & 255, null);
            if (hsb[2] < 0.45f) continue;
            float b = Math.min(1f, hsb[2] * 1.3f + 0.08f);
            float s = hsb[2] > 0.95f ? hsb[1] * 0.8f : hsb[1];
            out.setRGB(x, y, 0xFF000000 | (Color.HSBtoRGB(hsb[0], s, b) & 0xFFFFFF));
        }
        return out;
    }

    static void write(BufferedImage img, String rel) throws Exception {
        File f = tex.resolve(rel).toFile();
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println("geschrieben: " + rel);
    }

    /** [0]=humanoid, [1]=humanoid glow, [2]=leggings, [3]=leggings glow */
    static BufferedImage[] layers(String helmet, String chest, String legs, String boots, int[] key, int[] pal) throws Exception {
        Portal humMetal = portal(vanilla("entity/equipment/humanoid/netherite.png"), 1, STAR_DENSITY, false);
        // kein Stern auf dem Nasensteg des Helms (Kopf-Vorderseite, untere Hälfte): säße wie ein drittes Auge im Gesicht
        for (int y = 11; y < 16; y++) for (int x = 8; x < 16; x++) humMetal.star[y][x] = 0;
        stars(humMetal, 0);
        BufferedImage hum = humMetal.img;
        BufferedImage helmetTrim = recolor(vanilla("trims/entity/humanoid/" + helmet + ".png"), key, pal);
        BufferedImage chestTrim = recolor(vanilla("trims/entity/humanoid/" + chest + ".png"), key, pal);
        BufferedImage bootsTrim = recolor(vanilla("trims/entity/humanoid/" + boots + ".png"), key, pal);
        overlay(hum, helmetTrim, 0, 0, 64, 16);     // Kopf + Hut
        overlay(hum, bootsTrim, 0, 16, 16, 32);     // Beine (Schuhe)
        overlay(hum, chestTrim, 16, 16, 64, 32);    // Körper + Arme
        BufferedImage humGlow = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        overlay(humGlow, glow(helmetTrim), 0, 0, 64, 16);
        overlay(humGlow, glow(bootsTrim), 0, 16, 16, 32);
        overlay(humGlow, glow(chestTrim), 16, 16, 64, 32);
        starGlow(humMetal, humGlow, hum);

        Portal legMetal = portal(vanilla("entity/equipment/humanoid_leggings/netherite.png"), 2, STAR_DENSITY, false);
        stars(legMetal, 0);
        BufferedImage leg = legMetal.img;
        BufferedImage legTrim = recolor(vanilla("trims/entity/humanoid_leggings/" + legs + ".png"), key, pal);
        overlay(leg, legTrim, 0, 0, leg.getWidth(), leg.getHeight());
        BufferedImage legGlow = new BufferedImage(leg.getWidth(), leg.getHeight(), BufferedImage.TYPE_INT_ARGB);
        overlay(legGlow, glow(legTrim), 0, 0, leg.getWidth(), leg.getHeight());
        starGlow(legMetal, legGlow, leg);
        return new BufferedImage[]{hum, humGlow, leg, legGlow};
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        tex = root.resolve("src/main/resources/assets/hha_void/textures");
        String preview = null;
        String jarPath = System.getProperty("user.home") + "/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar";
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--preview")) preview = args[++i];
            else jarPath = args[i];
        }
        jar = new ZipFile(jarPath);

        BufferedImage keyImg = vanilla("trims/color_palettes/trim_palette.png");
        int[] key = new int[keyImg.getWidth()];
        for (int i = 0; i < key.length; i++) key[i] = keyImg.getRGB(i, 0) & 0xFFFFFF;
        BufferedImage amethyst = vanilla("trims/color_palettes/amethyst.png");
        int[] pal = palette(amethyst, 1.0);
        int[] palGlow = palette(amethyst, 1.2);

        if (preview != null) {
            preview(preview, key, pal, amethyst);
            jar.close();
            return;
        }

        BufferedImage[] l = layers(HELMET_TRIM, CHEST_TRIM, LEGS_TRIM, BOOTS_TRIM, key, pal);
        write(l[0], "entity/equipment/humanoid/void.png");
        write(l[1], "entity/equipment/humanoid/void_glow.png");
        write(l[2], "entity/equipment/humanoid_leggings/void.png");
        write(l[3], "entity/equipment/humanoid_leggings/void_glow.png");

        // ---- Item-Icons: Portal-Metall + Item-Trim-Overlay, 4 Frames (Trim normal / glühend, Sterne funkeln), interpoliert
        for (String piece : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
            BufferedImage strip = icon(piece, key, pal, palGlow);
            write(strip, "item/void_" + piece + ".png");
            Files.writeString(tex.resolve("item/void_" + piece + ".png.mcmeta"),
                "{\"animation\":{\"frametime\":26,\"interpolate\":true,\"frames\":[0,1,2,3]}}\n");
        }
        jar.close();
    }

    static BufferedImage icon(String piece, int[] key, int[] pal, int[] palGlow) throws Exception {
        BufferedImage netherite = vanilla("item/netherite_" + piece + ".png");
        BufferedImage trim = vanilla("trims/items/" + piece + "_trim.png");
        BufferedImage strip = new BufferedImage(16, 16 * ICON_FRAMES, BufferedImage.TYPE_INT_ARGB);
        int[][] pals = {pal, palGlow};
        for (int f = 0; f < ICON_FRAMES; f++) {
            // Frames 0..3: Trim normal / glühend / normal / glühend, dazu wandert die Helligkeit der Sterne (Funkeln)
            Portal metal = portal(netherite, 20 + piece.length(), STAR_DENSITY_ICON, true);
            stars(metal, f);
            BufferedImage frame = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            frame.getGraphics().drawImage(metal.img, 0, 0, null);
            overlay(frame, recolor(trim, key, pals[f % 2]), 0, 0, 16, 16);
            strip.getGraphics().drawImage(frame, 0, f * 16, null);
        }
        return strip;
    }

    // ------------------------------------------------------------------ Vorschau

    static void blit(BufferedImage dst, int dx, int dy, BufferedImage src, int sx, int sy, int w, int h, boolean mirror, boolean add) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = src.getRGB(sx + (mirror ? w - 1 - x : x), sy + y);
            if ((c >>> 24) == 0) continue;
            int px = dx + x, py = dy + y;
            if (add) {
                int d = dst.getRGB(px, py);
                int r = Math.min(255, ((d >> 16) & 255) + ((c >> 16) & 255));
                int g = Math.min(255, ((d >> 8) & 255) + ((c >> 8) & 255));
                int b = Math.min(255, (d & 255) + (c & 255));
                dst.setRGB(px, py, 0xFF000000 | (r << 16) | (g << 8) | b);
            } else dst.setRGB(px, py, over(dst.getRGB(px, py), c));
        }
    }

    /** Papierpuppe 16x32: front=true Vorderseite, sonst Rückseite. dark = Nacht (Basis abgedunkelt, Glow voll). */
    static BufferedImage doll(BufferedImage[] l, boolean front, boolean dark) {
        BufferedImage d = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = d.createGraphics();
        g.setColor(new Color(0x8A6A55));
        g.fillRect(4, 0, 8, 8); g.fillRect(0, 8, 16, 12); g.fillRect(4, 20, 8, 12);
        g.dispose();
        for (int pass = 0; pass < 2; pass++) {
            BufferedImage hum = l[pass], leg = l[2 + pass];
            boolean add = false; // eyes-Ebene: normales Alpha-Blending, volle Helligkeit
            int hx = front ? 8 : 24, bx = front ? 20 : 32, ax = front ? 44 : 52, lx = front ? 4 : 12;
            // Hose: Körper + Beine
            blit(d, 4, 8, leg, bx, 20, 8, 12, false, add);
            blit(d, front ? 4 : 8, 20, leg, lx, 20, 4, 12, false, add);
            blit(d, front ? 8 : 4, 20, leg, lx, 20, 4, 12, true, add);
            // Schuhe
            blit(d, front ? 4 : 8, 20, hum, lx, 20, 4, 12, false, add);
            blit(d, front ? 8 : 4, 20, hum, lx, 20, 4, 12, true, add);
            // Brust + Arme
            blit(d, 4, 8, hum, bx, 20, 8, 12, false, add);
            blit(d, front ? 0 : 12, 8, hum, ax, 20, 4, 12, false, add);
            blit(d, front ? 12 : 0, 8, hum, ax, 20, 4, 12, true, add);
            // Helm
            blit(d, 4, 0, hum, hx, 8, 8, 8, false, add);
            if (pass == 0 && dark) {
                for (int y = 0; y < 32; y++) for (int x = 0; x < 16; x++) {
                    int c = d.getRGB(x, y);
                    if ((c >>> 24) == 0) continue;
                    int r = (int) (((c >> 16) & 255) * 0.22), gg = (int) (((c >> 8) & 255) * 0.22), b = (int) ((c & 255) * 0.3);
                    d.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
                }
            }
        }
        return d;
    }

    static void preview(String out, int[] key, int[] pal, BufferedImage amethyst) throws Exception {
        int S = 10, cellW = 4 * (16 * S + 12) + 30, cellH = 32 * S + 40;
        BufferedImage img = new BufferedImage(cellW * 3, cellH * ((CANDIDATES.length + 2) / 3) + 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x5A6A78)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int i = 0; i < CANDIDATES.length; i++) {
            BufferedImage[] l = layers(CANDIDATES[i][0], CANDIDATES[i][1], CANDIDATES[i][2], CANDIDATES[i][3], key, pal);
            int ox = (i % 3) * cellW + 10, oy = (i / 3) * cellH + 24;
            g.setColor(Color.WHITE);
            g.drawString((i + 1) + ": Helm " + CANDIDATES[i][0] + " | Brust " + CANDIDATES[i][1] + " | Hose " + CANDIDATES[i][2] + " | Schuhe " + CANDIDATES[i][3], ox, oy - 6);
            BufferedImage[] dolls = {doll(l, true, false), doll(l, false, false), doll(l, true, true), doll(l, false, true)};
            for (int k = 0; k < 4; k++) {
                int x0 = ox + k * (16 * S + 12);
                if (k >= 2) { g.setColor(new Color(0x101018)); g.fillRect(x0 - 4, oy - 2, 16 * S + 8, 32 * S + 4); }
                for (int y = 0; y < 32; y++) for (int x = 0; x < 16; x++) {
                    int c = dolls[k].getRGB(x, y);
                    if ((c >>> 24) == 0) continue;
                    g.setColor(new Color(c, true));
                    g.fillRect(x0 + x * S, oy + y * S, S, S);
                }
            }
        }
        // Paletten: Vanilla-Amethyst oben, Void unten; daneben die Icons
        int py = img.getHeight() - 70;
        for (int i = 0; i < pal.length; i++) {
            g.setColor(new Color(amethyst.getRGB(i, 0) & 0xFFFFFF)); g.fillRect(10 + i * 30, py, 30, 28);
            g.setColor(new Color(pal[i])); g.fillRect(10 + i * 30, py + 30, 30, 28);
        }
        int[] palGlow = palette(amethyst, 1.2);
        int ix = 300;
        for (String piece : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
            BufferedImage strip = icon(piece, key, pal, palGlow);
            for (int f = 0; f < ICON_FRAMES; f++) {
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    int c = strip.getRGB(x, f * 16 + y);
                    if ((c >>> 24) == 0) continue;
                    g.setColor(new Color(c, true));
                    g.fillRect(ix + x * 4, py - 4 + y * 4, 4, 4);
                }
                ix += 70;
            }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("Vorschau: " + out);
    }
}
