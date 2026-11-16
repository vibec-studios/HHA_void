import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 3D-Modelle + Texturen des Void-Addons: Void Sword, Void Mace (Hand-Modelle und daraus gerenderte
 * Inventar-Icons), der Altar-Block "Ruf der Tiefe" und der Void-Barren.
 * Gleiches Prinzip wie im Schwester-Addon hha-blood (GenBloodStyle/GenSwordIcon/GenMace/GenTextures).
 *
 *   Schwert: Katana. Lange, schlanke, leicht zum Rücken gebogene schwarze Klinge mit violett leuchtender
 *            Schneide und Hamon-Linie, runde verzierte Tsuba aus dunklem Metall, langer schwarz umwickelter
 *            Griff (Rautenmuster) mit Endkappe. Schwarz-violette Void-Flammen umzüngeln die Klinge: zwei
 *            gekreuzte, hauchdünne Ebenen mit animierten Flammen-Pixeln (Cutout, leuchtend).
 *   Mace:    langer Kriegshammer. Schlanker Schaft aus Void-Metall mit Manschetten und Griffwicklung, am
 *            Schaftende ein vierzackiger Stern mit Edelstein. Kopf: auf der einen Seite ein Klotz mit breiter,
 *            flacher Schlagfläche (an den Seiten glühende Flammen-Glyphen), auf der anderen ein gestufter Dorn
 *            mit glühender Spitze; mittig auf beiden Seiten eine Rauten-Platte mit leuchtendem Auge, obenauf
 *            ein Flammenkamm aus Kristallzacken und animierten Void-Flammen.
 *   Altar:   Schwarzstein-Sockel, Obsidian-Schaft, darauf eine Stimmgabel aus Walknochen, zwischen deren
 *            Zinken ein Sternkristall schwebt. Leuchtende Rillen pulsieren von innen nach außen (der "Ruf").
 *            Bleibt in der Hitbox 2..14 x 0..13 x 2..14 (VoidAltarBlock.SHAPE).
 *   Barren:  dunkler Barren mit violett leuchtender Lache obenauf, Glanzlicht + funkelnde Sterne.
 *
 * Die Waffen-Modelle stehen AUFRECHT (Längsachse = y, Mitte x=8/z=8). Die Vanilla-Sprites liegen auf der
 * Diagonale; das entspricht dem aufrechten Modell, um -45° um z gedreht (Drehpunkt 8,8,8). Die 3rd-Person-Werte
 * leiten sich deshalb aus Vanilla `handheld` bzw. `handheld_mace` mit z-Rotation minus 45° ab (Griffpunkt an der
 * Vanilla-Handposition); die Ego-Sicht hat eigene Werte (siehe SWORD_FP / MACE_FP und die Hand-Vorschau).
 *
 * Texturen werden aus der 3D-Position jedes Texels gemalt (2 Texel pro Modell-Einheit), 8 Frames
 * (Schneiden-Welle, Flammen, Strahlen-Puls, Rillen-Puls). Icons: orthografischer Render des Modells (um
 * die Längsachse gedreht, von oben geneigt, auf die Diagonale gelegt), 8x8 Samples pro Icon-Pixel,
 * dominante Farbe, 1-px-Umriss um die festen Teile (Flammen bekommen keinen Umriss). Ausnahme: das Mace-Icon
 * wird Pixel für Pixel gesetzt (maceIcon), weil der Hammer für einen 32x32-Render zu feingliedrig ist.
 *
 * Schreibt:
 *   models/item/void_sword_3d.json, models/item/void_mace_3d.json, models/block/void_altar.json
 *   textures/item/void_sword_3d.png, void_mace_3d.png, textures/block/void_altar.png   Modell-Atlanten (+mcmeta)
 *   textures/item/void_sword.png, void_mace.png                                        Icons 32x32 (+mcmeta)
 *   textures/item/void_ingot.png (+mcmeta)
 *
 * Aufruf (im Projektordner):
 *   java tools/textures/GenVoidModels.java .                   alles schreiben
 *   java tools/textures/GenVoidModels.java . --preview <dir>   nur Vorschau-PNGs nach <dir> (schreibt nichts ins Projekt)
 *   java tools/textures/GenVoidModels.java . --hand <dir>      Hand-Vorschau: Ego-Sicht + Spielerpuppe mit den Display-Werten
 */
public class GenVoidModels {

    static final int PX = 2;          // Texel pro Modell-Einheit
    static final int FRAMES = 8;      // Animations-Frames (Atlas + Icon)
    static int ATLAS = 64;            // Atlas-Kantenlänge
    static final int ICON = 32, SS = 8;

    /**
     * Größe in der Hand, 3rd Person / Ego-Sicht (Vanilla: Schwert 0.85 / 0.68, Mace 1.0 / 0.9). In der Ego-Sicht
     * kleiner als Vanilla, weil ein 3D-Modell dort wuchtiger wirkt; der Hammer ist in 3rd Person bewusst riesig.
     */
    static final double SWORD_3RD = 0.95, SWORD_1ST = 1.3, MACE_3RD = 1.55, MACE_1ST = 0.46;
    /** Griffpunkte (aufrechtes Modell): wo Vanilla die Hand hat / wo unsere Hand sitzen soll */
    static final double[] SWORD_GRIP_VANILLA = {8, 1, 8}, SWORD_GRIP = {8, -1.5, 8};
    static final double[] MACE_GRIP_VANILLA = {8, 1.5, 8}, MACE_GRIP = {8, -2.5, 8};
    /** 3rd Person fasst die Hand den (riesigen) Hammer weiter unten an der Griffwicklung, damit der Knauf nicht im Boden steckt */
    static final double[] MACE_GRIP_3RD = {8, -4, 8};
    /**
     * Display-Werte {rx, ry, rz, tx, ty, tz, Größe}; eingestellt mit der Hand-Vorschau (--hand), im Spiel geprüft.
     *
     * 3rd Person (siehe [held], aus Vanilla handheld [0,-90,55] T[0,4,0.5] S0.85 bzw. handheld_mace T[0,4,1] S1):
     *   Katana: wie Vanilla (z-Rotation 55-45 = 10), nur größer.
     *   Hammer: 1.55 (vorher 0.85) und um 50° steiler nach oben gestellt (z = -40): der Kopf steht vorn über
     *           Kopfhöhe statt waagrecht vor dem Bauch, der Knauf bleibt über dem Boden. z > 10 kippt nach unten!
     *
     * Ego-Sicht (siehe [pose]): mit den Vanilla-Werten steht die Waffe in der Ebene neben dem Spieler und kippt
     * mit der Spitze ZUR Kamera - ein flaches Sprite sieht man dabei von der Seite, ein langes 3D-Modell ragt
     * aus dem Bild (Katana: nur ein dicker Klingenstumpf am Rand) bzw. zeigt mit dem Dorn in die Kamera (Hammer).
     *   Katana (Runde 4, Mats: "größer, angewinkelt wie in Third Person"): gleiche Haltung wie am Spielermodell -
     *           die Klinge zeigt vom Spieler weg nach vorn und steigt nur ~30° an (pitch 58), Schneide unten, Rücken
     *           mit den Flammen oben. Aus den Augen des Spielers läuft sie von der Tsuba rechts unten schräg zur
     *           Bildmitte. Flachseite 38° zum Spieler gedreht (Hamon sichtbar), Größe 1.3 (die Klinge liegt in der Tiefe).
     *   Hammer (Runde 4, Mats: "von hinten, ein bisschen angewinkelt zur Seite"): der Blick zum Kopf geht ~38° nach
     *           rechts, "genau von hinten" wäre deshalb roll -38. Mit roll -8 schaut man auf die Rückseite (Dorn zum
     *           Spieler/nach rechts) und sieht schräg die linke Seite mit Auge und Flammen-Glyphen. roll > +15 = Profil.
     */
    static final double[] SWORD_TP = held(55, new double[]{0, 4, 0.5}, 0.85, SWORD_GRIP_VANILLA, new double[]{0, -90, 10}, SWORD_3RD, SWORD_GRIP);
    static final double[] MACE_TP = held(55, new double[]{0, 4, 1}, 1, MACE_GRIP_VANILLA, new double[]{0, -90, -40}, MACE_3RD, MACE_GRIP_3RD);
    static final double[] SWORD_FP = pose(6, 58, 38, new double[]{4.2, -3.6, 0.5}, SWORD_1ST, SWORD_GRIP);
    static final double[] MACE_FP = pose(-3, 28, -8, new double[]{3.5, -1.8, -2}, MACE_1ST, MACE_GRIP);

    // ------------------------------------------------------------------ Palette
    static final int[] OBS = {0x07050D, 0x0E0A18, 0x171026, 0x24163C};                              // Obsidian / Leere
    static final int[] GLOW = {0x4A17A8, 0x5D1DC4, 0x7834DD, 0x9B47FF, 0xCD53FF, 0xE796FF, 0xF9DCFF}; // Violett -> Pink-Weiß
    static final int[] BONE = {0x5A4E6E, 0x8A7F96, 0xBDB3BE, 0xE4DDD8, 0xFBF7EE};                   // Walknochen
    static final int[] NETH = {0x19161B, 0x27232A, 0x37323A, 0x4A444D, 0x5F5862};                   // Netherit
    static final int[] WRAP = {0x3D1B7A, 0x5B2BB0, 0x8245DC};                                       // Griffwicklung
    static final int[] STONE = {0x161219, 0x221D27, 0x2E2835, 0x3D3646, 0x4E4659};                  // polierter Schwarzstein
    static final int[] AME = {0x1E0B45, 0x321268, 0x4A1C94, 0x6A2CC4, 0x8F4AE8, 0xC08AFF, 0xF0D4FF};  // Amethyst-Zacken
    static final int[] FLAME = {0x08050F, 0x160C2B, 0x2C1658, 0x4C2496, 0x7A3CD6, 0xB870FF, 0xF0D0FF};  // Void-Flammen
    static final int[] NOVA = {0x4A17A8, 0x7426D6, 0xA436EE, 0xD44CF4, 0xF76FE6, 0xFFAEEF, 0xFFF0FA};   // Strahlen: Violett -> Magenta -> Weiß
    static final int OUTLINE = 0x0B0714;
    /** Material-Rückgabe für "durchsichtig" (Cutout) */
    static final int CLEAR = -1;

    // ------------------------------------------------------------------ Modell
    interface Mat { int col(El e, double[] p, double[] w, int face, int fr); }

    static final String[] FACES = {"north", "south", "west", "east", "up", "down"};
    static final int N = 0, S = 1, W = 2, E = 3, U = 4, D = 5;

    static class El {
        String name; double[] from, to; double[] rot; /* {x,y,z} Grad um org, immer nur eine Achse */
        double[] org = {8, 8, 8};
        Mat mat; boolean emissive, fx; int[][] uv = new int[6][]; boolean[] skip = new boolean[6];
        El(String name, double x1, double y1, double z1, double x2, double y2, double z2, Mat mat) {
            this.name = name; from = new double[]{x1, y1, z1}; to = new double[]{x2, y2, z2}; this.mat = mat;
        }
        El glow() { emissive = true; return this; }
        /** Effekt-Element (Flammen): zählt nicht zur Silhouette, bekommt im Icon keinen Umriss */
        El fx() { fx = true; return this; }
        El rotY(double deg) { rot = new double[]{0, deg, 0}; return this; }
        El rotZ(double deg) { rot = new double[]{0, 0, deg}; return this; }
        El rotX(double deg) { rot = new double[]{deg, 0, 0}; return this; }
        El at(double x, double y, double z) { org = new double[]{x, y, z}; return this; }
        El noFaces(int... f) { for (int i : f) skip[i] = true; return this; }
        /** Ecken einer Fläche: {TL, TR, BL} in lokalen Koordinaten (Minecrafts Standard-UV-Ausrichtung) */
        double[][] corners(int f) {
            double x1 = from[0], y1 = from[1], z1 = from[2], x2 = to[0], y2 = to[1], z2 = to[2];
            switch (f) {
                case S: return new double[][]{{x1, y2, z2}, {x2, y2, z2}, {x1, y1, z2}};
                case N: return new double[][]{{x2, y2, z1}, {x1, y2, z1}, {x2, y1, z1}};
                case W: return new double[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z1}};
                case E: return new double[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z2}};
                case U: return new double[][]{{x1, y2, z1}, {x2, y2, z1}, {x1, y2, z2}};
                default: return new double[][]{{x1, y1, z2}, {x2, y1, z2}, {x1, y1, z1}};
            }
        }
        double[] world(double[] p) {
            if (rot == null) return p;
            double[] v = {p[0] - org[0], p[1] - org[1], p[2] - org[2]};
            v = mul(mul(mul(Rz(rot[2]), Ry(rot[1])), Rx(rot[0])), v);
            return new double[]{v[0] + org[0], v[1] + org[1], v[2] + org[2]};
        }
    }

    // ------------------------------------------------------------------ Mathe
    static double[][] Rx(double d) { double a = Math.toRadians(d), c = Math.cos(a), s = Math.sin(a); return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}}; }
    static double[][] Ry(double d) { double a = Math.toRadians(d), c = Math.cos(a), s = Math.sin(a); return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}}; }
    static double[][] Rz(double d) { double a = Math.toRadians(d), c = Math.cos(a), s = Math.sin(a); return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}}; }
    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }
    static double[] mul(double[][] a, double[] v) {
        return new double[]{a[0][0] * v[0] + a[0][1] * v[1] + a[0][2] * v[2], a[1][0] * v[0] + a[1][1] * v[1] + a[1][2] * v[2], a[2][0] * v[0] + a[2][1] * v[1] + a[2][2] * v[2]};
    }
    static double hash(int a, int b, int c) {
        int h = a * 374761393 + b * 668265263 + c * 2147483647 + 0x9E3779B9;
        h = (h ^ (h >>> 13)) * 1274126177; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
    static int q(double v) { return (int) Math.floor(v * PX + 1e-6); }
    static int pick(int[] pal, double idx) { return pal[Math.max(0, Math.min(pal.length - 1, (int) Math.floor(idx)))]; }

    // ------------------------------------------------------------------ Materialien
    static Mat NETHERITE = (e, p, w, f, fr) -> {
        double v = (p[1] - e.from[1]) / Math.max(1e-6, e.to[1] - e.from[1]);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 31);
        double idx = 1.2 + v * 1.4 + (n - 0.5) * 1.6 + (f == U ? 1 : f == D ? -1 : 0);
        return pick(NETH, idx);
    };
    /** Griff: dunkles Netherit mit spiralförmiger violetter Wicklung */
    static Mat GRIP = (e, p, w, f, fr) -> {
        if (f == U || f == D) return NETH[1];
        int off = f == S ? 0 : f == E ? 1 : f == N ? 2 : 3;
        int s = Math.floorMod(q(p[1]) + off, 4);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f);
        if (s == 0) return n < 0.3 ? WRAP[1] : WRAP[2];
        if (s == 1) return n < 0.5 ? WRAP[0] : WRAP[1];
        return n < 0.5 ? NETH[0] : NETH[1];
    };
    static Mat GEM = (e, p, w, f, fr) -> {
        double t = 0.5 + 0.5 * Math.sin(2 * Math.PI * fr / FRAMES);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f);
        return pick(GLOW, 3.6 + t * 1.6 + n * 1.2);
    };

    // ---- Katana
    // Ausrichtung (aufrechtes Modell): Schneide zeigt nach -x, Rücken nach +x, die Spitze weicht zum Rücken aus.
    // So hält die Hand die Waffe richtig herum: mit den Vanilla-Display-Werten zeigt -x in 3rd Person nach
    // unten/vorn und in der Ego-Sicht vom Spieler weg (dieselbe Seite, auf der Vanilla-Äxte ihre Schneide haben).
    static final double K_Y0 = 3.5, K_Y1 = 21.0, K_TIP = 2.5, K_CURVE = 2.5;   // Klinge von..bis, Länge der Spitze, Auslenkung zum Rücken
    /** Reichweite der Flammen am Klingenrücken / an der Schneide */
    static final double FLAME_BACK = 2.5, FLAME_EDGE = 0.9;

    /** Mittellinie der Klinge bei Höhe y (Viertel-Einheiten-Raster): Parabel, die Spitze weicht zum Rücken (+x) aus */
    static double kx(double y) {
        double t = Math.max(0, Math.min(1, (y - K_Y0) / (K_Y1 - K_Y0)));
        return 8 + Math.round(K_CURVE * t * t * 4) / 4.0;
    }

    /** Klingenkörper: schwarzer Stahl mit hellerer Gratlinie (Shinogi) und vereinzelten violetten Funken */
    static Mat KBLADE = (e, p, w, f, fr) -> {
        int ix = q(p[0]), iy = q(p[1]);
        double n = hash(ix, iy, f == N ? 3 : 0);
        if (f == E) return n < 0.5 ? OBS[2] : OBS[3];                                  // Klingenrücken
        int c = n < 0.5 ? OBS[0] : n < 0.85 ? OBS[1] : OBS[2];
        double u = (p[0] - e.from[0]) / Math.max(1e-6, e.to[0] - e.from[0]);
        if ((f == N || f == S) && u > 0.34 && u < 0.67) c = n < 0.7 ? OBS[3] : 0x35215C;
        if (hash(ix, iy, 23) < 0.04) c = 0x4A2C8C;
        return c;
    };
    /** Schneide + Hamon: außen gleißend, innen die gewellte Härtelinie; eine Welle läuft zur Spitze */
    static Mat HAMON = (e, p, w, f, fr) -> {
        double wave = 0.5 + 0.5 * Math.sin(2 * Math.PI * ((double) fr / FRAMES - p[1] / 8.0));
        double u = (p[0] - e.from[0]) / Math.max(1e-6, e.to[0] - e.from[0]);
        if (u < 0.5 || f == W || f == U || e.to[0] - e.from[0] < 0.6) return pick(GLOW, 4.4 + wave * 2.4);
        int k = Math.floorMod(q(p[1]), 4);
        return pick(GLOW, (k < 2 ? 1.2 : 2.7) + wave * 1.6);
    };
    /** Tsuka-Wicklung: schwarzes Band über Kreuz, in den Rauten scheint violette Rochenhaut durch */
    static Mat TSUKA = (e, p, w, f, fr) -> {
        if (f == U || f == D) return NETH[0];
        boolean wide = f == N || f == S;
        double u = wide ? (p[0] - e.from[0]) / (e.to[0] - e.from[0]) : (p[2] - e.from[2]) / (e.to[2] - e.from[2]);
        int cols = wide ? 3 : 2, i = Math.min(cols - 1, (int) (u * cols)), row = q(p[1]);
        if (Math.floorMod(i + row, 2) == 0) return hash(i, row, f) < 0.5 ? 0x121016 : 0x1B1821;
        return wide && i == 1 ? (Math.floorMod(row, 4) == 1 ? WRAP[2] : WRAP[1]) : 0x2D2836;
    };
    /** Tsuba: dunkles Metall, heller Rand, acht Amethyst-Einlagen im Wechsel */
    static Mat TSUBA = (e, p, w, f, fr) -> {
        double r = Math.hypot(w[0] - 8, w[2] - 8);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 3);
        if (f == U || f == D) {
            if (r > 1.8) return n < 0.5 ? NETH[3] : NETH[4];
            int sector = (int) Math.floor(Math.atan2(w[2] - 8, w[0] - 8) / (Math.PI / 4) + 8.5);
            if (r > 1.0 && r < 1.55 && sector % 2 == 0) return AME[4];
            return n < 0.5 ? NETH[1] : NETH[2];
        }
        return n < 0.4 ? NETH[3] : NETH[4];
    };
    static Mat HABAKI = (e, p, w, f, fr) -> pick(AME, 2.4 + (p[1] - e.from[1]) * 1.6 + hash(q(p[0]), q(p[1]), q(p[2]) + f) * 1.2);

    /** weiches, in y kachelbares Rauschen (Periode py Zellen) */
    static double vnoise(double x, double y, int py, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        double a = hash(x0, Math.floorMod(y0, py), seed), b = hash(x0 + 1, Math.floorMod(y0, py), seed);
        double c = hash(x0, Math.floorMod(y0 + 1, py), seed), d = hash(x0 + 1, Math.floorMod(y0 + 1, py), seed);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }
    /** weiches 1D-Rauschen mit Periode [period] */
    static double noise1(double x, int period, int seed) {
        int x0 = (int) Math.floor(x);
        double f = x - x0; f = f * f * (3 - 2 * f);
        double a = hash(Math.floorMod(x0, period), seed, 3), b = hash(Math.floorMod(x0 + 1, period), seed, 3);
        return a + (b - a) * f;
    }
    /**
     * Void-Flammen als hauchdünne Ebene in der Klingenfläche (8 Frames, nahtlos): zusammenhängende Zungen, die
     * am Klingenrücken (+x, in der Hand oben) emporlodern und zur Spitze hin wandern - an der Klinge violett,
     * zur Zungenspitze hin schwarz. An der Schneide nur ein schmaler Saum. Dazu wenige aufsteigende Funken.
     */
    static Mat FLAME_M = (e, p, w, f, fr) -> {
        double y = p[1], lat = p[0] - kx(y);
        boolean back = lat > 0;
        double d = Math.abs(lat) - 1.0;                                      // Abstand von der Klingenkante
        if (d < 0) return CLEAR;
        double taper = y < K_Y0 + 2.5 ? (y - K_Y0) / 2.5 : y > K_Y1 - 0.5 ? Math.max(0, (K_Y1 + 1.5 - y) / 2.0) : 1;
        double reach = (back ? FLAME_BACK : FLAME_EDGE) * taper;
        int ix = q(p[0]), iy = q(y);
        if (back && d < reach + 1.5 && taper > 0.3 && hash(ix, Math.floorMod(iy - fr * 2, 16), 40) < 0.012)
            return GLOW[hash(ix, iy, fr) < 0.5 ? 5 : 6];                      // Funken
        if (reach <= 0) return CLEAR;
        // Zungenlänge an dieser Stelle; mit dem Abstand lehnen sich die Zungen zur Spitze
        double yy = (y - d * 1.0) / 1.15 - (double) fr / FRAMES * 8;
        double n = noise1(yy, 8, back ? 5 : 6) * 0.7 + noise1(yy * 2.0 + 1.7, 16, back ? 7 : 8) * 0.3;
        double len = reach * Math.max(0, (n - 0.36) / 0.5);
        if (d >= len) return CLEAR;
        double k = 1 - d / len;                                             // 1 an der Klinge, 0 an der Zungenspitze
        return pick(FLAME, 0.4 + k * 4.4 + (d < 0.5 ? 1.0 : 0) + (hash(ix, iy, 9) - 0.5) * 1.0);
    };

    static List<El> sword() {
        List<El> m = new ArrayList<>();
        // Kashira (Endkappe) + Tsuka (Griff) + Menuki (Zierknopf) + Fuchi (Zwinge)
        m.add(new El("kashira_gem", 7.6, -7.0, 7.7, 8.4, -6.75, 8.3, GEM).glow());
        m.add(new El("kashira", 7.2, -6.75, 7.3, 8.8, -6.0, 8.7, NETHERITE));
        m.add(new El("tsuka", 7.3, -6.0, 7.4, 8.7, 1.4, 8.6, TSUKA));
        m.add(new El("menuki", 7.75, -2.75, 7.3, 8.25, -2.0, 8.7, GEM).glow().noFaces(W, E, U, D));
        m.add(new El("fuchi", 7.2, 1.4, 7.3, 8.8, 2.0, 8.7, NETHERITE));
        // Tsuba: achteckige Scheibe
        m.add(new El("tsuba", 5.75, 2.0, 7.0, 10.25, 2.6, 9.0, TSUBA));
        m.add(new El("tsuba", 7.0, 2.0, 5.75, 9.0, 2.6, 10.25, TSUBA));
        m.add(new El("tsuba", 6.25, 2.0, 6.25, 9.75, 2.6, 9.75, TSUBA));
        // Habaki (Klingenzwinge)
        m.add(new El("habaki", 6.9, 2.6, 7.45, 9.1, K_Y0, 8.55, HABAKI));
        // Klinge: Abschnitte folgen der Biegung; Schneide links (-x), Rücken rechts (+x)
        double end = K_Y1 - K_TIP, ya = K_Y0;
        while (ya < end - 1e-6) {
            double yb = ya + 0.5;
            while (yb < end - 1e-6 && kx(yb + 0.25) == kx(ya + 0.25)) yb += 0.5;
            double cx = kx(ya + 0.25);
            m.add(new El("blade", cx - 0.25, ya, 7.55, cx + 1.0, yb, 8.45, KBLADE));
            m.add(new El("edge", cx - 1.0, ya, 7.7, cx - 0.25, yb, 8.3, HAMON).glow().noFaces(E));
            ya = yb;
        }
        // Kissaki: die Schneide läuft in drei Stufen zum Rücken, die Spitze glüht
        double cx = kx(end + 0.25);
        m.add(new El("blade", cx + 0.1, end, 7.55, cx + 1.0, end + 1.0, 8.45, KBLADE));
        m.add(new El("edge", cx - 0.55, end, 7.7, cx + 0.1, end + 1.0, 8.3, HAMON).glow().noFaces(E));
        cx = kx(end + 1.25);
        m.add(new El("blade", cx + 0.5, end + 1.0, 7.6, cx + 1.0, end + 1.75, 8.4, KBLADE));
        m.add(new El("edge", cx - 0.05, end + 1.0, 7.7, cx + 0.5, end + 1.75, 8.3, HAMON).glow().noFaces(E));
        cx = kx(K_Y1);
        m.add(new El("tip", cx + 0.45, end + 1.75, 7.7, cx + 1.0, K_Y1, 8.3, HAMON).glow());
        // Void-Flammen: eine hauchdünne Ebene in der Klingenfläche
        m.add(new El("flame", 8 - 1 - FLAME_EDGE - 0.5, K_Y0, 8, 8 + K_CURVE + 1 + FLAME_BACK + 1.5, K_Y1 + 1.5, 8, FLAME_M).glow().fx().noFaces(W, E, U, D));
        return m;
    }

    // ---- Kriegshammer-Mace
    // Ausrichtung (aufrechtes Modell) wie beim Katana: -x zeigt in der Hand nach vorn/unten. Dort sitzt die
    // flache Schlagfläche, der Dorn zeigt nach +x. Die Augen-Platten liegen auf den beiden z-Seiten des Kopfs.
    static final double[] HEAD = {8, 14, 8}, POMMEL = {8, -7.5, 8};
    /** Oberkante des Kopfgehäuses (Fuß des Flammenkamms), Wurzel und Spitze des Dorns */
    static final double CREST_Y = 16.5, SPIKE_X0 = 11.5, SPIKE_X1 = 17.25;
    /** Void-Metall: fast schwarzes Netherit mit violettem Stich */
    static final int[] VMET = {0x0B0812, 0x130E1E, 0x1C152B, 0x271D3B, 0x35294F, 0x473867, 0x5E4C85};

    /** Texel auf der Fläche: {i, j, Breite, Höhe}; j zählt bei Seitenflächen von oben */
    static int[] texel(El e, double[] p, int f) {
        int a = (f == W || f == E) ? 2 : 0, b = (f == U || f == D) ? 2 : 1;
        int wd = Math.max(1, (int) Math.round((e.to[a] - e.from[a]) * PX)), ht = Math.max(1, (int) Math.round((e.to[b] - e.from[b]) * PX));
        int i = Math.min(wd - 1, (int) ((p[a] - e.from[a]) * PX)), j = Math.min(ht - 1, (int) ((b == 1 ? e.to[1] - p[1] : p[2] - e.from[2]) * PX));
        return new int[]{i, j, wd, ht};
    }
    /** Abstand des Texels zum Flächenrand (0 = Randtexel) */
    static int rim(int[] t) { return Math.min(Math.min(t[0], t[2] - 1 - t[0]), Math.min(t[1], t[3] - 1 - t[1])); }

    /** Void-Metall mit Pixel-Fase: Oberkante hell, Unterkante dunkel, vereinzelt ein violetter Funke */
    static Mat VOIDMETAL = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 31);
        double idx = 2.1 + (n - 0.5) * 1.5 + (f == U ? 1.0 : f == D ? -1.2 : 0);
        if (f != U && f != D && t[3] > 2) { if (t[1] == 0) idx += 1.4; if (t[1] == t[3] - 1) idx -= 1.2; }
        if ((f == U || f == D) && t[2] > 2 && t[3] > 2 && rim(t) == 0) idx += 0.9;
        if (hash(q(p[0]) + 5, q(p[1]), q(p[2]) + f) < 0.025) return 0x5A3AA0;
        return pick(VMET, idx);
    };
    /** Hammerklotz: heller Rahmen, vertieftes dunkles Feld */
    static Mat HBLOCK = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        int r = rim(t);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 17);
        double idx = (r == 0 ? 3.7 : r == 1 ? 0.9 : 1.9) + (n - 0.5) * 1.3 + (f == U ? 0.8 : f == D ? -0.8 : 0);
        if (r == 0 && f != U && f != D && t[1] == 0) idx += 0.9;
        return pick(VMET, idx);
    };
    /** die flache Schlagfläche: glattes, helleres Metall mit Fase und ein paar Scharten */
    static Mat STRIKE = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 11);
        if (f != W) return pick(VMET, 3.6 + (n - 0.5) * 1.2 + (f == U ? 1 : 0));
        int r = rim(t);
        if (r == 0) return t[1] == 0 ? VMET[6] : t[1] == t[3] - 1 ? VMET[3] : VMET[5];
        return n < 0.08 ? VMET[2] : n < 0.55 ? VMET[4] : VMET[5];
    };
    /** Flammen-Glyphen, die an den Seiten des Hammerklotzes von der Unterkante emporzüngeln (8 Texel breit) */
    static final String[] EMBER = {
        "......#.",
        "..#...#.",
        "..#..##.",
        ".##..##.",
        ".##.###.",
        ".##.####",
        "###.####",
        "########",
        "########",
    };
    static Mat EMBER_M = (e, p, w, f, fr) -> {
        int i = Math.max(0, Math.min(7, q(p[0]) - q(e.from[0]))), k = q(p[1]) - q(e.from[1]);
        if (k >= EMBER.length || EMBER[EMBER.length - 1 - k].charAt(i) != '#') return CLEAR;
        boolean tip = k == EMBER.length - 1 || EMBER[EMBER.length - 2 - k].charAt(i) != '#';
        if (tip && k > 2 && hash(i, k, fr) < 0.3) return CLEAR;                       // Zungenspitzen flackern
        double pulse = 0.5 + 0.5 * Math.sin(2 * Math.PI * ((double) fr / FRAMES - k / 9.0));
        return pick(GLOW, 5.2 - k * 0.5 + pulse * 1.3 + (hash(i, k, 3) - 0.5) * 0.8);
    };
    /** Augen-Platte: Metallrahmen, heller Lidring; in der Mitte sitzt (eigenes Element) das Auge */
    static Mat EYE_PLATE = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 7);
        if (f != N && f != S) return pick(VMET, 3.4 + (n - 0.5) * 1.4);
        int r = rim(t);
        if (r == 0) return n < 0.5 ? VMET[4] : VMET[5];
        if (r == 1) return n < 0.5 ? 0xA794D2 : 0xC3B2E8;
        return VMET[0];
    };
    /** das Auge: leuchtende Iris (pulsiert), schwarze Pupille, ein Glanzpunkt */
    static Mat EYE = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double pulse = 0.5 + 0.5 * Math.sin(2 * Math.PI * fr / FRAMES);
        if (f != N && f != S) return pick(GLOW, 2.5 + pulse);
        if (rim(t) > 0) return 0x05030A;
        if (t[0] == (f == S ? 0 : t[2] - 1) && t[1] == 0) return GLOW[6];
        return pick(GLOW, 3.3 + pulse * 1.5 + hash(t[0], t[1], 5) * 0.9);
    };
    /** Dorn: an der Wurzel Void-Metall, zur Spitze hin Amethyst bis gleißend */
    static final int[] SPIKE_RAMP = {VMET[2], VMET[3], VMET[4], 0x4A2C8C, AME[3], AME[4], AME[5], AME[6]};
    static Mat SPIKE_M = (e, p, w, f, fr) -> {
        double t = (w[0] - SPIKE_X0) / (SPIKE_X1 - SPIKE_X0);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 13);
        return pick(SPIKE_RAMP, 0.6 + t * 7.2 + (n - 0.5) * 1.2 + (f == U ? 0.8 : f == D ? -0.8 : 0));
    };
    /** Schaft: dunkles Void-Metall, um das sich eine feine Amethyst-Ader windet */
    static Mat SHAFT = (e, p, w, f, fr) -> {
        if (f == U || f == D) return VMET[1];
        double u = (f == N || f == S) ? (p[0] - e.from[0]) / (e.to[0] - e.from[0]) : (p[2] - e.from[2]) / (e.to[2] - e.from[2]);
        int col = (f == S ? 0 : f == E ? 2 : f == N ? 4 : 6) + ((f == S || f == W) == (u < 0.5) ? 0 : 1);
        if (Math.floorMod(q(p[1]) - col * 2, 16) == 0) return AME[4];
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 5);
        return n < 0.45 ? VMET[1] : n < 0.8 ? VMET[2] : VMET[3];
    };
    /** Amethyst: am Fuß dunkel, zur Spitze hin hell (lo..hi = Höhenbereich) */
    static Mat amethyst(double lo, double hi) {
        return (e, p, w, f, fr) -> {
            double h = (w[1] - lo) / (hi - lo);
            double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 13);
            return pick(AME, 1.8 + h * 4.6 + (n - 0.5) * 1.4 + (f == W || f == S ? 0.5 : 0));
        };
    }
    /**
     * aufsteigende Void-Flamme (8 Frames, nahtlos): s = seitlicher Abstand zur Flammenmitte, h = Höhe über dem
     * Fuß. Rauschballen steigen auf und reißen nach oben hin ab; am Fuß violett-hell, an der Spitze dunkelviolett.
     */
    static int risingFlame(double s, double h, double halfWidth, double reach, int fr, int seed) {
        if (h < 0 || Math.abs(s) >= halfWidth) return CLEAR;
        double env = reach * (1 - sq(s / halfWidth));
        if (h >= env) return CLEAR;
        double k = h / env;
        double n = vnoise(s * 1.5 + 40, h * 0.8 - fr * 0.5, 4, seed);
        if (n < 0.24 + k * 0.54) return CLEAR;
        return pick(FLAME, Math.max(2, 6.2 - k * 5.6 + (n - 0.5) * 1.6));     // nicht bis Schwarz: wirkt vor hellem Himmel wie Ruß
    }
    /** Flammenkamm über dem Kopf (zwei gekreuzte Ebenen) */
    static Mat CREST_FLAME = (e, p, w, f, fr) -> {
        boolean alongX = e.to[2] - e.from[2] < 0.01;
        double s = alongX ? p[0] - 8.5 : p[2] - 8;
        return risingFlame(s, p[1] - CREST_Y, alongX ? 4.5 : 3.0, alongX ? 6.5 : 5.5, fr, alongX ? 11 : 12);
    };
    /** kleine Flammen, die von den Seitenarmen des Knauf-Sterns aufsteigen */
    static Mat POMMEL_FLAME = (e, p, w, f, fr) -> {
        double h = p[1] - (POMMEL[1] + 0.75);
        int a = risingFlame(p[0] - (POMMEL[0] - 3.25), h, 1.25, 2.75, fr, 13);
        return a != CLEAR ? a : risingFlame(p[0] - (POMMEL[0] + 3.25), h, 1.25, 2.75, fr, 14);
    };
    static Mat POMMEL_GEM = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double pulse = 0.5 + 0.5 * Math.sin(2 * Math.PI * fr / FRAMES);
        if ((f == N || f == S) && rim(t) > 0) return GLOW[6];
        return pick(GLOW, 2.8 + pulse * 1.6 + hash(t[0], t[1], f) * 0.8);
    };

    static void cuff(List<El> m, double y0, double y1, double half, boolean band) {
        m.add(new El("cuff", 8 - half, y0, 8 - half, 8 + half, y1, 8 + half, VOIDMETAL));
        double ym = (y0 + y1) / 2;
        if (band) m.add(new El("cuff_band", 8 - half - 0.06, ym - 0.25, 8 - half - 0.06, 8 + half + 0.06, ym + 0.25, 8 + half + 0.06, GEM).glow().noFaces(U, D));
    }
    /** ein Arm des Knauf-Sterns: dünner Steg, Verdickung, Spitze. dir: 0 = -x, 1 = +x, 2 = -y */
    static void starArm(List<El> m, int dir) {
        double[] r = {1.25, 2.5, 3.5, 4.75}, half = {0.5, 0.75, 0.25};
        for (int i = 0; i < 3; i++) {
            double a = r[i], b = r[i + 1], h = half[i], px = POMMEL[0], py = POMMEL[1];
            Mat mat = i == 2 ? GEM : VOIDMETAL;
            El e = dir == 2 ? new El("star_arm", px - h, py - b, 8 - h, px + h, py - a, 8 + h, mat)
                : dir == 0 ? new El("star_arm", px - b, py - h, 8 - h, px - a, py + h, 8 + h, mat)
                : new El("star_arm", px + a, py - h, 8 - h, px + b, py + h, 8 + h, mat);
            if (i == 2) e.glow();
            m.add(e);
        }
    }

    static List<El> mace() {
        List<El> m = new ArrayList<>();
        double hy = HEAD[1], py = POMMEL[1];
        // Knauf: vierzackiger Stern. Rautenplatte mit Edelstein, drei Arme (der vierte Zacken ist der Schaft)
        m.add(new El("star_plate", 6.75, py - 1.25, 7.25, 9.25, py + 1.25, 8.75, EYE_PLATE).rotZ(45).at(8, py, 8));
        m.add(new El("star_gem", 7.25, py - 0.75, 7.15, 8.75, py + 0.75, 8.85, POMMEL_GEM).glow().rotZ(45).at(8, py, 8).noFaces(W, E, U, D));
        for (int d = 0; d < 3; d++) starArm(m, d);
        m.add(new El("star_flame", 3.25, py + 0.75, 8, 12.75, py + 3.5, 8, POMMEL_FLAME).glow().fx().noFaces(W, E, U, D));
        // Schaft mit Manschetten, im unteren Drittel die Griffwicklung
        m.add(new El("shaft", 7.5, py + 1.0, 7.5, 8.5, hy - 2.5, 8.5, SHAFT));
        cuff(m, -6.0, -5.0, 0.9, true);
        m.add(new El("grip", 7.4, -5.0, 7.4, 8.6, 0.0, 8.6, GRIP));
        cuff(m, 0.0, 0.75, 0.8, false);
        cuff(m, 5.0, 5.75, 0.8, false);
        cuff(m, 9.25, 10.75, 1.0, true);
        m.add(new El("flange", 6.5, 10.75, 6.5, 9.5, 11.5, 9.5, VOIDMETAL));
        // Kopfgehäuse mit Augen-Platte auf beiden Seiten (um 45° gedrehte Raute)
        m.add(new El("housing", 5.5, hy - 2.5, 6.0, 10.5, hy + 2.5, 10.0, VOIDMETAL));
        m.add(new El("eye_plate", 6.0, hy - 2.0, 5.5, 10.0, hy + 2.0, 10.5, EYE_PLATE).rotZ(45).at(8, hy, 8));
        m.add(new El("eye", 7.0, hy - 1.0, 5.4, 9.0, hy + 1.0, 10.6, EYE).glow().rotZ(45).at(8, hy, 8).noFaces(W, E, U, D));
        // Hammerseite (-x): Hals, Klotz, flache Schlagfläche; an den Klotzseiten glühende Flammen-Glyphen
        m.add(new El("hammer_neck", 4.5, hy - 2.0, 6.5, 5.5, hy + 2.0, 9.5, VOIDMETAL));
        m.add(new El("hammer", 0.5, hy - 3.5, 5.0, 4.5, hy + 3.5, 11.0, HBLOCK));
        m.add(new El("hammer_face", 0.0, hy - 3.0, 5.5, 0.5, hy + 3.0, 10.5, STRIKE));
        m.add(new El("hammer_ember", 0.5, hy - 3.5, 4.96, 4.5, hy + 1.0, 11.04, EMBER_M).glow().noFaces(W, E, U, D));
        // Dornseite (+x): Kragen, drei sich verjüngende Stufen, glühende Spitze
        m.add(new El("spike_collar", 10.5, hy - 2.0, 6.5, SPIKE_X0, hy + 2.0, 9.5, VOIDMETAL));
        m.add(new El("spike", SPIKE_X0, hy - 1.5, 7.0, 13.0, hy + 1.5, 9.0, SPIKE_M));
        m.add(new El("spike", 13.0, hy - 1.0, 7.25, 14.5, hy + 1.0, 8.75, SPIKE_M));
        m.add(new El("spike", 14.5, hy - 0.5, 7.5, 16.0, hy + 0.5, 8.5, SPIKE_M));
        m.add(new El("spike_tip", 16.0, hy - 0.25, 7.75, SPIKE_X1, hy + 0.25, 8.25, SPIKE_M).glow());
        // Flammenkamm: Deckplatte, Kristallzacken (zum Dorn hin geneigt), darum zwei gekreuzte Flammen-Ebenen
        m.add(new El("cap", 6.0, CREST_Y, 6.5, 10.0, CREST_Y + 0.5, 9.5, VOIDMETAL));
        Mat cr = amethyst(CREST_Y, CREST_Y + 5.25);
        double c = CREST_Y + 0.5;
        m.add(new El("crest", 5.75, c, 7.25, 10.25, c + 0.75, 8.75, cr).glow());
        m.add(new El("crest", 5.75, c + 0.75, 7.5, 7.0, c + 2.25, 8.5, cr).glow());
        m.add(new El("crest", 6.25, c + 2.25, 7.75, 7.0, c + 3.0, 8.25, cr).glow());
        m.add(new El("crest", 7.5, c + 0.75, 7.25, 9.0, c + 3.0, 8.75, cr).glow());
        m.add(new El("crest", 8.0, c + 3.0, 7.5, 9.0, c + 4.0, 8.5, cr).glow());
        m.add(new El("crest", 8.5, c + 4.0, 7.75, 9.0, c + 4.75, 8.25, cr).glow());
        m.add(new El("crest", 9.5, c + 0.75, 7.5, 10.25, c + 1.75, 8.5, cr).glow());
        m.add(new El("crest", 9.75, c + 1.75, 7.75, 10.25, c + 2.5, 8.25, cr).glow());
        m.add(new El("crest", 7.75, c, 6.5, 8.75, c + 1.5, 7.25, cr).glow());
        m.add(new El("crest", 7.75, c, 8.75, 8.75, c + 1.5, 9.5, cr).glow());
        m.add(new El("crest_flame", 4.0, CREST_Y, 8, 13.0, CREST_Y + 6.5, 8, CREST_FLAME).glow().fx().noFaces(W, E, U, D));
        m.add(new El("crest_flame", 8, CREST_Y, 5.0, 8, CREST_Y + 5.5, 11.0, CREST_FLAME).glow().fx().noFaces(N, S, U, D));
        return m;
    }

    // ---- Mace-Icon
    // Der Hammer ist für einen Render in 32x32 zu feingliedrig (Auge, Dorn und Stern verschwimmen). Das Icon wird
    // deshalb Pixel für Pixel gesetzt - dieselbe Gestalt auf der Vanilla-Diagonale: Knauf-Stern unten links, Kopf
    // oben rechts, Hammerklotz nach links oben, Dorn nach rechts unten, Flammenkamm in Verlängerung des Schafts.
    // Gerechnet wird im gedrehten Raster um die Kopfmitte: u läuft den Schaft entlang (zum Kamm hin positiv),
    // v quer dazu (zur Schlagfläche hin positiv); ein Schritt sind 0,7 Pixel.
    static final int ICON_HX = 20, ICON_HY = 11, ICON_PX = 4, ICON_PY = 27;
    /** Länge der Kristallzacken des Kamms je v = -5..5 (zum Dorn hin geneigt) */
    static final int[] ICON_CREST = {0, 2, 5, 9, 7, 4, 2, 5, 3, 1, 0};
    /** bis wohin (u) die Flammen-Glyphen im Klotz je v = 9..12 reichen */
    static final int[] ICON_EMBER = {-1, 2, -3, 0};

    /** Frame des Mace-Icons: {feste Teile, alles inklusive Flammen} als ARGB, ohne Umriss */
    static int[][] maceIcon(int fr) {
        int[] solid = new int[ICON * ICON], all = new int[ICON * ICON];
        double pulse = 0.5 + 0.5 * Math.sin(2 * Math.PI * fr / FRAMES);
        for (int y = 0; y < ICON; y++) for (int x = 0; x < ICON; x++) {
            int dx = x - ICON_HX, dy = y - ICON_HY, u = dx - dy, v = -(dx + dy), au = Math.abs(u), av = Math.abs(v);
            int eye = Math.max(Math.abs(dx), Math.abs(dy));
            int qx = x - ICON_PX, qy = y - ICON_PY, aqx = Math.abs(qx), aqy = Math.abs(qy);
            int crest = ICON_CREST[Math.max(-5, Math.min(5, v)) + 5];
            int c = -1; boolean flame = false;
            if (eye <= 3) {                                                         // Augen-Platte
                boolean lit = dx == -eye || dy == -eye;
                c = eye == 3 ? (lit ? VMET[5] : VMET[3]) : eye == 2 ? (lit ? 0xC3B2E8 : 0xA794D2)
                    : eye == 1 ? pick(GLOW, 3.3 + pulse * 1.5 + (dx == -1 && dy == -1 ? 1.6 : 0)) : 0x05030A;
            } else if (v >= 7 && v <= 14 && au <= 7) {                              // Hammerklotz + Schlagfläche
                if (v >= 13) c = au == 7 ? VMET[4] : v == 14 ? 0x8573B0 : VMET[6];
                else if (au >= 6) c = u > 0 ? VMET[5] : VMET[2];
                else if (v <= 8) c = VMET[3];
                else {
                    int reach = ICON_EMBER[v - 9];
                    if (u <= reach && !(u >= reach - 1 && hash(v, u, fr) < 0.3)) c = pick(GLOW, 4.9 - (u + 5) * 0.5 + pulse * 0.9);
                    else c = hash(x, y, 2) < 0.5 ? VMET[1] : VMET[2];
                }
            } else if (v >= 5 && v <= 6 && au <= 3) c = VMET[2];                    // Hals
            else if (v <= -5 && v >= -6 && au <= 4) c = u >= 0 ? VMET[4] : VMET[2]; // Dorn: Kragen, drei Stufen, Spitze
            else if (v <= -7 && v >= -9 && au <= 3) c = u >= 0 ? VMET[5] : VMET[3];
            else if (v <= -10 && v >= -12 && au <= 2) c = u >= 0 ? 0x6A45B8 : 0x4A2C8C;
            else if (v <= -13 && v >= -15 && au <= 1) c = u >= 0 ? AME[5] : AME[4];
            else if (v <= -16 && v >= -18 && u == 0) c = GLOW[6];
            else if (u >= 5 && u <= 6 && av <= 3) c = VMET[4];                      // Deckplatte
            else if (au <= 5 && av <= 5) c = (u > 0 || v > 0) ? VMET[3] : VMET[2];  // Gehäuse
            else if (u >= 7 && av <= 5 && u <= 6 + crest)                           // Kristallzacken
                c = pick(AME, 2.6 + (u - 7) * 0.5 + (v > 0 ? 0.6 : 0) + pulse * 0.6);
            else if (u >= 7 && av <= 6 && u <= 10 + crest) {                        // Flammen über dem Kamm
                if (hash(Math.floorMod(u - 2 * fr, 16), v, 61) < 0.4) { c = pick(FLAME, 5.2 - (u - 6 - crest) * 0.8); flame = true; }
            } else if (Math.max(aqx, aqy) <= 1) {                                   // Knauf: Platte mit Edelstein
                c = aqx == 0 && aqy == 0 ? pick(GLOW, 4.4 + pulse * 1.6) : (qx == -1 || qy == -1) ? VMET[5] : VMET[3];
            } else if (aqx >= 2 && aqx <= 3 && aqy >= 2 && aqy <= 3 && !(qx > 0 && qy < 0)) {   // drei Arme des Sterns
                c = aqx == 3 && aqy == 3 ? pick(GLOW, 4.0 + pulse * 1.6) : aqx == 2 && aqy == 2 ? VMET[5] : VMET[3];
            } else if (qx <= -2 && qy <= -2 && aqx + aqy <= 9 && Math.abs(aqx - aqy) <= 1 && (aqx > 3 || aqy > 3)) {   // Flämmchen am Stern
                if (hash(Math.floorMod(aqx + aqy - fr, 8), aqx - aqy, 67) < 0.45) { c = FLAME[aqx + aqy > 8 ? 3 : 5]; flame = true; }
            } else if (u <= -6 && u >= -29) {                                       // Schaft, Manschetten, Griffwicklung
                if (u >= -8 && av <= 2) c = u == -7 ? pick(GLOW, 4.0 + pulse * 1.5) : v > 0 ? VMET[5] : VMET[4];
                else if (((u >= -15 && u <= -14) || u <= -28) && v >= -1 && v <= 2) c = v > 0 ? VMET[5] : VMET[3];
                else if (v == 0 || v == 1) {
                    if (u <= -19 && u >= -27) c = Math.floorMod(u, 4) < 2 ? (v == 1 ? WRAP[2] : WRAP[1]) : VMET[1];
                    else c = v == 1 ? VMET[4] : VMET[2];
                }
            }
            if (c < 0) continue;
            all[y * ICON + x] = 0xFF000000 | c;
            if (!flame) solid[y * ICON + x] = 0xFF000000 | c;
        }
        return new int[][]{solid, all};
    }
    static double sq(double v) { return v * v; }

    // ------------------------------------------------------------------ Atlas
    /** malt alle Flächen, packt sie und liefert die Frames des Atlas */
    static BufferedImage[] atlas(List<El> model) {
        class Face { El e; int f, w, h; int[][] px; }
        List<Face> faces = new ArrayList<>();
        for (El e : model) for (int f = 0; f < 6; f++) {
            if (e.skip[f]) continue;
            double[][] c = e.corners(f);
            double wu = dist(c[0], c[1]), hv = dist(c[0], c[2]);
            Face fa = new Face(); fa.e = e; fa.f = f;
            fa.w = Math.max(1, (int) Math.round(wu * PX)); fa.h = Math.max(1, (int) Math.round(hv * PX));
            fa.px = new int[FRAMES][fa.w * fa.h];
            for (int fr = 0; fr < FRAMES; fr++) for (int j = 0; j < fa.h; j++) for (int i = 0; i < fa.w; i++) {
                double s = (i + 0.5) / fa.w, t = (j + 0.5) / fa.h;
                double[] p = new double[3];
                for (int k = 0; k < 3; k++) p[k] = c[0][k] + s * (c[1][k] - c[0][k]) + t * (c[2][k] - c[0][k]);
                // knapp nach innen rücken, damit Rundung an Flächenkanten stabil bleibt
                for (int k = 0; k < 3; k++) p[k] = Math.max(e.from[k] + 1e-4, Math.min(e.to[k] - 1e-4, p[k]));
                int col = e.mat.col(e, p, e.world(p), f, fr);
                fa.px[fr][j * fa.w + i] = col == CLEAR ? 0 : 0xFF000000 | col;
            }
            faces.add(fa);
        }
        // gleiche Flächen teilen sich ein Rechteck; Regal-Packer, hohe Flächen zuerst
        List<Face> order = new ArrayList<>(faces);
        order.sort((a, b) -> b.h != a.h ? b.h - a.h : b.w - a.w);
        Map<String, int[]> seen = new HashMap<>();
        BufferedImage[] out = new BufferedImage[FRAMES];
        for (int i = 0; i < FRAMES; i++) out[i] = new BufferedImage(ATLAS, ATLAS, BufferedImage.TYPE_INT_ARGB);
        int x = 0, y = 0, shelf = 0;
        for (Face fa : order) {
            String key = fa.w + "x" + fa.h + ":" + Arrays.deepHashCode(fa.px) + ":" + Arrays.hashCode(fa.px[0]);
            int[] r = seen.get(key);
            if (r == null) {
                if (x + fa.w > ATLAS) { x = 0; y += shelf; shelf = 0; }
                if (y + fa.h > ATLAS) throw new IllegalStateException("Atlas zu klein (" + ATLAS + ")");
                r = new int[]{x, y, fa.w, fa.h};
                for (int fr = 0; fr < FRAMES; fr++) for (int j = 0; j < fa.h; j++) for (int i = 0; i < fa.w; i++) out[fr].setRGB(x + i, y + j, fa.px[fr][j * fa.w + i]);
                x += fa.w; shelf = Math.max(shelf, fa.h);
                seen.put(key, r);
            }
            fa.e.uv[fa.f] = r;
        }
        System.out.println("  Atlas belegt bis Zeile " + (y + shelf) + " von " + ATLAS + ", " + faces.size() + " Flächen, " + seen.size() + " Rechtecke");
        return out;
    }
    static double dist(double[] a, double[] b) { return Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2])); }

    // ------------------------------------------------------------------ JSON
    static String num(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }
    static String vec(double... v) { StringBuilder b = new StringBuilder("["); for (int i = 0; i < v.length; i++) b.append(i > 0 ? ", " : "").append(num(v[i])); return b.append("]").toString(); }

    static String json(List<El> model, String tex, String particle, String display, String head) {
        StringBuilder b = new StringBuilder();
        b.append("{\n").append(head).append("  \"textures\": {\n    \"0\": \"").append(tex).append("\",\n    \"particle\": \"").append(particle).append("\"\n  },\n  \"elements\": [\n");
        for (int i = 0; i < model.size(); i++) {
            El e = model.get(i);
            b.append("    {\n      \"name\": \"").append(e.name).append("\",\n      \"from\": ").append(vec(e.from)).append(",\n      \"to\": ").append(vec(e.to)).append(",\n");
            if (e.rot != null) {
                String axis = e.rot[0] != 0 ? "x" : e.rot[1] != 0 ? "y" : "z";
                double ang = e.rot[0] != 0 ? e.rot[0] : e.rot[1] != 0 ? e.rot[1] : e.rot[2];
                // Vanilla-Konvention: Winkel im Bereich -180..180
                while (ang > 180) ang -= 360;
                b.append("      \"rotation\": {\"origin\": ").append(vec(e.org)).append(", \"axis\": \"").append(axis).append("\", \"angle\": ").append(num(ang)).append("},\n");
            }
            if (e.emissive) b.append("      \"light_emission\": 15,\n      \"shade\": false,\n");
            b.append("      \"faces\": {\n");
            boolean first = true;
            for (int f = 0; f < 6; f++) {
                if (e.skip[f]) continue;
                int[] r = e.uv[f];
                double k = 16.0 / ATLAS;
                b.append(first ? "" : ",\n").append("        \"").append(FACES[f]).append("\": {\"uv\": ")
                    .append(vec(r[0] * k, r[1] * k, (r[0] + r[2]) * k, (r[1] + r[3]) * k)).append(", \"texture\": \"#0\"}");
                first = false;
            }
            b.append("\n      }\n    }").append(i < model.size() - 1 ? "," : "").append("\n");
        }
        b.append("  ]").append(display == null ? "" : ",\n  \"display\": " + display).append("\n}\n");
        return b.toString();
    }

    /**
     * Display-Block. Ausgangspunkt sind die Vanilla-Werte (z-Rotation, Verschiebung t, Größe s) für 3rd/1st
     * Person; die z-Rotation wird um 45° reduziert (aufrechtes Modell statt Diagonal-Sprite).
     * Eigene Größen n3/n1 und ein eigener Griffpunkt: der Punkt [grip] unseres Modells landet genau dort,
     * wo Vanilla den Punkt [vgrip] seines Sprites in der Hand hält:
     *   t' = t + R * (s * (vgrip - Mitte) - n * (grip - Mitte))        (Mitte = 8,8,8; R = Ry(-90) * Rz(z))
     */
    static String display(double[] tp, double[] fp) {
        return "{\n"
            + "    \"thirdperson_righthand\": " + displayEntry(tp) + ",\n"
            + "    \"thirdperson_lefthand\": " + displayEntry(leftOf(tp)) + ",\n"
            + "    \"firstperson_righthand\": " + displayEntry(fp) + ",\n"
            + "    \"firstperson_lefthand\": " + displayEntry(leftOf(fp)) + ",\n"
            + "    \"head\": {\"rotation\": [0, 180, 0], \"translation\": [0, 13, 7], \"scale\": [1, 1, 1]}\n"
            + "  }";
    }
    /** ein Eintrag aus {rx, ry, rz, tx, ty, tz, Größe} */
    static String displayEntry(double[] d) {
        return "{\"rotation\": " + vec(d[0], d[1], d[2]) + ", \"translation\": " + vec(d[3], d[4], d[5]) + ", \"scale\": " + vec(d[6], d[6], d[6]) + "}";
    }
    /**
     * JSON-Werte der linken Hand, sodass sie das exakte Spiegelbild der rechten zeigt.
     * Das Spiel negiert bei der linken Hand die x-Verschiebung sowie y- und z-Rotation DES LINKEN EINTRAGS
     * (Transformation.apply), das Modell selbst wird nicht gespiegelt. Gesucht ist die Drehung L = M*R*Z
     * (M = Spiegelung an x im Bild, Z = Spiegelung des Modells an seiner z-Symmetrieebene; beide Waffen sind
     * in z symmetrisch). Deren Euler-Winkel werden mit negiertem y/z eingetragen, damit das Spiel sie zurückdreht.
     * Für Vanilla-artige Rotationen [0,-90,z] ergibt das wie in `handheld.json` [0,90,-z].
     */
    static double[] leftOf(double[] d) {
        double[][] r = rotXYZ(d), l = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) l[i][j] = (i == 0 ? -1 : 1) * r[i][j] * (j == 2 ? -1 : 1);
        double[] e = euler(l);
        return new double[]{e[0], -e[1], -e[2], d[3], d[4], d[5], d[6]};
    }
    /** Euler-Winkel {x,y,z} in Grad mit m = Rx*Ry*Rz (Minecrafts Display-Rotation) */
    static double[] euler(double[][] m) {
        double a, b, c;
        if (Math.abs(m[0][2]) > 0.999999) { a = 0; b = m[0][2] > 0 ? 90 : -90; c = Math.toDegrees(Math.atan2(m[1][0], m[1][1])); }
        else {
            b = Math.toDegrees(Math.asin(m[0][2]));
            a = Math.toDegrees(Math.atan2(-m[1][2], m[2][2]));
            c = Math.toDegrees(Math.atan2(-m[0][1], m[0][0]));
        }
        if (Math.abs(a) > 90) {                                   // gleichwertige Darstellung mit kleinem x-Winkel
            a -= Math.signum(a) * 180; b = 180 - b; c -= Math.signum(c) * 180;
            if (b > 180) b -= 360;
        }
        return new double[]{r2(a), r2(b), r2(c)};
    }
    static double r2(double v) { double r = Math.round(v * 100) / 100.0; return r == 0 ? 0 : r; }
    /**
     * Ego-Sicht als Haltung beschrieben statt als Euler-Winkel. Ausgangslage wie Vanilla (Waffe aufrecht in der
     * Ebene neben dem Spieler, -x des Modells = Schneide/Schlagfläche zeigt nach vorn), dann
     *   roll:  Drehung um die Längsachse (positiv: die linke Flachseite dreht sich zum Spieler)
     *   pitch: Spitze nach vorn (vom Spieler weg) geneigt
     *   lean:  Spitze nach links (zur Bildmitte) geneigt
     * also R = Rz(lean) * Rx(-pitch) * Ry(-90 + roll). [hand] = wo der Griffpunkt [grip] liegt, in 1/16 Block
     * relativ zum Ego-Ursprung des Spiels (0.56, -0.52, -0.72 vor der Kamera; Vanilla-Schwertgriff = [1.1, -1.3, -0.5]).
     */
    static double[] pose(double lean, double pitch, double roll, double[] hand, double n, double[] grip) {
        double[][] r = mul(Rz(lean), mul(Rx(-pitch), Ry(-90 + roll)));
        double[] b = mul(r, new double[]{n * (grip[0] - 8), n * (grip[1] - 8), n * (grip[2] - 8)}), e = euler(r);
        return new double[]{e[0], e[1], e[2], r2(hand[0] - b[0]), r2(hand[1] - b[1]), r2(hand[2] - b[2]), n};
    }
    /**
     * Display-Werte für "in der Hand gehalten": Rotation [rot], Größe n, und die Verschiebung so, dass der
     * Griffpunkt [grip] unseres Modells genau dort landet, wo Vanilla den Punkt [vgrip] seines Sprites hält.
     * Vanilla-Werte: z-Rotation zV (Diagonal-Sprite, deshalb -45° fürs aufrechte Modell), Verschiebung tV, Größe sV.
     *   t' = tV + Ry(-90)*Rz(zV-45) * sV * (vgrip - Mitte)  -  Rx*Ry*Rz(rot) * n * (grip - Mitte)        (Mitte = 8,8,8)
     */
    static double[] held(double zV, double[] tV, double sV, double[] vgrip, double[] rot, double n, double[] grip) {
        double[] a = new double[3], b = new double[3];
        for (int k = 0; k < 3; k++) { a[k] = sV * (vgrip[k] - 8); b[k] = n * (grip[k] - 8); }
        a = mul(mul(Ry(-90), Rz(zV - 45)), a); b = mul(rotXYZ(rot), b);
        return new double[]{rot[0], rot[1], rot[2], tV[0] + a[0] - b[0], tV[1] + a[1] - b[1], tV[2] + a[2] - b[2], n};
    }

    // ------------------------------------------------------------------ Renderer
    static class Quad { double[] a, b, d, n; int[] uv; boolean emissive, fx; int flat; /* != 0: einfarbig (Hand-Vorschau) */ }

    static List<Quad> quads(List<El> model) {
        List<Quad> qs = new ArrayList<>();
        double[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        for (El e : model) for (int f = 0; f < 6; f++) {
            if (e.skip[f]) continue;
            double[][] c = e.corners(f);
            Quad qd = new Quad();
            qd.a = e.world(c[0]); qd.b = e.world(c[1]); qd.d = e.world(c[2]);
            double[] o = e.world(e.org.clone()), nn = e.world(new double[]{e.org[0] + normals[f][0], e.org[1] + normals[f][1], e.org[2] + normals[f][2]});
            qd.n = new double[]{nn[0] - o[0], nn[1] - o[1], nn[2] - o[2]};
            qd.uv = e.uv[f]; qd.emissive = e.emissive; qd.fx = e.fx;
            qs.add(qd);
        }
        return qs;
    }

    /** Ansicht fürs Icon: um die Längsachse gedreht, von oben geneigt, dann auf die Vanilla-Diagonale gelegt */
    static double[][] iconView(double turn, double tilt) { return mul(Rz(-45), mul(Rx(tilt), Ry(turn))); }

    /**
     * Orthografischer Render. Liefert w*h ARGB; pro Pixel ss*ss Samples, dominante Farbe (min. 38 % Deckung).
     * fit = {Skala px/Einheit, vx bei Pixel 0, vy bei Pixel 0 (oben)}; solidOnly lässt Effekt-Elemente weg.
     */
    static int[] render(List<Quad> qs, BufferedImage tex, double[][] view, double[] fit, int w, int h, int ss, boolean solidOnly) {
        int n = qs.size();
        double[][] A = new double[n][], B = new double[n][], Dd = new double[n][];
        double[] shade = new double[n];
        double[] L = {-0.36, 0.74, 0.57};
        for (int i = 0; i < n; i++) {
            Quad qd = qs.get(i);
            A[i] = mul(view, sub8(qd.a)); B[i] = mul(view, sub8(qd.b)); Dd[i] = mul(view, sub8(qd.d));
            double[] nv = mul(view, qd.n);
            double dot = Math.max(0, nv[0] * L[0] + nv[1] * L[1] + nv[2] * L[2]);
            shade[i] = qd.emissive ? 1 : Math.round((0.60 + 0.40 * dot) * 12) / 12.0;
        }
        int[] out = new int[w * h];
        Map<Integer, Integer> cnt = new HashMap<>();
        for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
            cnt.clear();
            int covered = 0;
            for (int sy = 0; sy < ss; sy++) for (int sx = 0; sx < ss; sx++) {
                double vx = fit[1] + (px + (sx + 0.5) / ss) / fit[0], vy = fit[2] - (py + (sy + 0.5) / ss) / fit[0];
                double bestZ = -1e9; int best = 0;
                for (int i = 0; i < n; i++) {
                    if (solidOnly && qs.get(i).fx) continue;
                    double ux = B[i][0] - A[i][0], uy = B[i][1] - A[i][1], wx = Dd[i][0] - A[i][0], wy = Dd[i][1] - A[i][1];
                    double det = ux * wy - uy * wx;
                    if (Math.abs(det) < 1e-9) continue;
                    double rx = vx - A[i][0], ry = vy - A[i][1];
                    double s = (rx * wy - ry * wx) / det, t = (ux * ry - uy * rx) / det;
                    if (s < 0 || s >= 1 || t < 0 || t >= 1) continue;
                    double z = A[i][2] + s * (B[i][2] - A[i][2]) + t * (Dd[i][2] - A[i][2]);
                    if (z <= bestZ) continue;
                    int[] r = qs.get(i).uv;
                    int c = tex.getRGB(r[0] + Math.min(r[2] - 1, (int) (s * r[2])), r[1] + Math.min(r[3] - 1, (int) (t * r[3])));
                    if ((c >>> 24) == 0) continue;                          // Cutout
                    bestZ = z;
                    double k = shade[i];
                    best = 0xFF000000 | ((int) (((c >> 16) & 255) * k) << 16) | ((int) (((c >> 8) & 255) * k) << 8) | (int) ((c & 255) * k);
                }
                if (bestZ > -1e8) { covered++; cnt.merge(best, 1, Integer::sum); }
            }
            if (covered >= ss * ss * 0.38) {
                int bc = 0, bn = -1;
                for (var en : cnt.entrySet()) if (en.getValue() > bn || (en.getValue() == bn && lum(en.getKey()) > lum(bc))) { bn = en.getValue(); bc = en.getKey(); }
                out[py * w + px] = bc;
            }
        }
        return out;
    }
    static double[] sub8(double[] p) { return new double[]{p[0] - 8, p[1] - 8, p[2] - 8}; }
    static int lum(int c) { return ((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255); }

    /** Bildausschnitt so wählen, dass das Modell w x h (abzüglich Rand) füllt */
    static double[] fit(List<Quad> qs, double[][] view, int w, int h, double margin) {
        double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
        for (Quad qd : qs) {
            if (qd.fx) continue;                                            // Flammen-Ebenen bestimmen den Ausschnitt nicht
            double[] c4 = {qd.b[0] + qd.d[0] - qd.a[0], qd.b[1] + qd.d[1] - qd.a[1], qd.b[2] + qd.d[2] - qd.a[2]};
            for (double[] p : new double[][]{qd.a, qd.b, qd.d, c4}) {
                double[] v = mul(view, sub8(p));
                x0 = Math.min(x0, v[0]); x1 = Math.max(x1, v[0]); y0 = Math.min(y0, v[1]); y1 = Math.max(y1, v[1]);
            }
        }
        double sc = Math.min((w - 2 * margin) / (x1 - x0), (h - 2 * margin) / (y1 - y0));
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        return new double[]{sc, cx - w / 2.0 / sc, cy + h / 2.0 / sc};
    }

    /** Umriss um die Silhouette [px] (nur feste Teile); gezeichnet wird in [all] (mit Effekten), wo dort noch nichts ist */
    static int[] outline(int[] px, int[] all, int w, int h) {
        int[] out = all.clone();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if ((px[y * w + x] >>> 24) != 0 || (all[y * w + x] >>> 24) != 0) continue;
            boolean nb = (x > 0 && (px[y * w + x - 1] >>> 24) != 0) || (x < w - 1 && (px[y * w + x + 1] >>> 24) != 0)
                || (y > 0 && (px[(y - 1) * w + x] >>> 24) != 0) || (y < h - 1 && (px[(y + 1) * w + x] >>> 24) != 0);
            if (nb) out[y * w + x] = 0xFF000000 | OUTLINE;
        }
        return out;
    }

    // ------------------------------------------------------------------ Ausgabe
    static BufferedImage strip(BufferedImage[] frames) {
        int w = frames[0].getWidth(), h = frames[0].getHeight();
        BufferedImage s = new BufferedImage(w, h * frames.length, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < frames.length; i++) s.getGraphics().drawImage(frames[i], 0, i * h, null);
        return s;
    }
    static BufferedImage img(int[] px, int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, w, h, px, 0, w);
        return b;
    }
    static void big(Graphics2D g, BufferedImage im, int x, int y, int sc) {
        for (int j = 0; j < im.getHeight(); j++) for (int i = 0; i < im.getWidth(); i++) {
            int c = im.getRGB(i, j);
            if ((c >>> 24) == 0) continue;
            g.setColor(new Color(c, true)); g.fillRect(x + i * sc, y + j * sc, sc, sc);
        }
    }

    static void build(Path root, Path preview, String name, List<El> model, String display, double turn, double tilt, int atlasTicks, int iconTicks) throws Exception {
        System.out.println(name + ": " + model.size() + " Elemente");
        BufferedImage[] atlas = atlas(model);
        List<Quad> qs = quads(model);
        double[][] view = iconView(turn, tilt);
        double[] fit = fit(qs, view, ICON, ICON, 1.5);
        BufferedImage[] icons = new BufferedImage[FRAMES];
        if (name.equals("void_mace")) {                                          // von Hand gesetztes Icon, siehe maceIcon
            for (int f = 0; f < FRAMES; f++) { int[][] pa = maceIcon(f); icons[f] = img(outline(pa[0], pa[1], ICON, ICON), ICON, ICON); }
        } else {
            int[] solid = render(qs, atlas[0], view, fit, ICON, ICON, SS, true);     // Silhouette ist in allen Frames gleich
            for (int f = 0; f < FRAMES; f++) icons[f] = img(outline(solid, render(qs, atlas[f], view, fit, ICON, ICON, SS, false), ICON, ICON), ICON, ICON);
        }

        if (preview != null) {
            Files.createDirectories(preview);
            int P = 420;
            BufferedImage sheet = new BufferedImage(P * 4 + 40, P + ICON * 8 + 60, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(new Color(0x59636E)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            g.setColor(new Color(0x14121A)); g.fillRect(P * 3 + 30, 0, P + 10, P + 20);
            double[][][] views = {iconView(turn, tilt), iconView(0, 0), iconView(90, 0), iconView(turn + 180, tilt)};
            for (int v = 0; v < 4; v++) {
                double[] ft = fit(qs, views[v], P, P, 8);
                big(g, img(render(qs, atlas[v == 3 ? 4 : 0], views[v], ft, P, P, 1, false), P, P), 10 + v * (P + 10), 10, 1);
            }
            for (int f = 0; f < FRAMES; f++) big(g, icons[f], 10 + f * (ICON * 6 + 8), P + 30, 6);
            for (int f = 0; f < 2; f++) big(g, icons[f * 4], 10 + 8 * (ICON * 6 + 8) + f * 40, P + 30, 1);
            for (int f = 0; f < 2; f++) big(g, icons[f * 4], 10 + 8 * (ICON * 6 + 8) + f * 80, P + 80, 2);
            g.dispose();
            ImageIO.write(sheet, "png", preview.resolve(name + "_sheet.png").toFile());
            BufferedImage at = new BufferedImage(ATLAS * 6 * 2 + 10, ATLAS * 6, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = at.createGraphics();
            g2.setColor(new Color(0x59636E)); g2.fillRect(0, 0, at.getWidth(), at.getHeight());
            big(g2, atlas[0], 0, 0, 6); big(g2, atlas[4], ATLAS * 6 + 10, 0, 6);
            g2.dispose();
            ImageIO.write(at, "png", preview.resolve(name + "_atlas.png").toFile());
            System.out.println("  Vorschau: " + preview.resolve(name + "_sheet.png"));
            return;
        }
        Path assets = root.resolve("src/main/resources/assets/hha_void");
        Path tex = assets.resolve("textures/item");
        Files.writeString(assets.resolve("models/item/" + name + "_3d.json"), json(model, "hha_void:item/" + name + "_3d", "hha_void:item/" + name, display, ""));
        ImageIO.write(strip(atlas), "png", tex.resolve(name + "_3d.png").toFile());
        Files.writeString(tex.resolve(name + "_3d.png.mcmeta"), "{\"animation\":{\"frametime\":" + atlasTicks + ",\"interpolate\":true}}\n");
        ImageIO.write(strip(icons), "png", tex.resolve(name + ".png").toFile());
        Files.writeString(tex.resolve(name + ".png.mcmeta"), "{\"animation\":{\"frametime\":" + iconTicks + ",\"interpolate\":true}}\n");
        System.out.println("  geschrieben: models/item/" + name + "_3d.json, textures/item/" + name + "_3d.png, textures/item/" + name + ".png");
    }

    // ------------------------------------------------------------------ Altar "Ruf der Tiefe"
    /** Puls, der von innen (Phase 0) nach außen (Phase 6) wandert */
    static double ripple(int fr, double phase) { return 0.5 + 0.5 * Math.cos(2 * Math.PI * (fr - phase) / FRAMES); }

    static Mat rippleMat(double phase) {
        return (e, p, w, f, fr) -> pick(GLOW, 2.3 + ripple(fr, phase) * 3.4 + hash(q(w[0]), q(w[1]), q(w[2])) * 0.9);
    }
    /** polierter Schwarzstein mit Fase; der Sockel trägt umlaufend violette Runen */
    static Mat BLACKSTONE = (e, p, w, f, fr) -> {
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 5);
        double idx = 1.6 + (n - 0.5) * 2.0;
        if (f == U) {
            double bx = Math.min(p[0] - e.from[0], e.to[0] - p[0]), bz = Math.min(p[2] - e.from[2], e.to[2] - p[2]);
            if (Math.min(bx, bz) < 0.5) idx += 1.3;
        } else if (f != D) {
            if (e.to[1] - p[1] < 0.5) idx += 1.2;
            if (p[1] - e.from[1] < 0.5) idx -= 1.0;
            if (e.name.equals("base") && p[1] > 0.5 && p[1] < 1.5) {
                double u = (f == N || f == S) ? p[0] : p[2];
                int c = q(u) - 4, cell = Math.floorDiv(c, 4), in = Math.floorMod(c, 4);
                boolean top = p[1] >= 1.0;
                double g = hash(cell, f, 77);
                boolean on = in == 1 || (in == 2 && (top ? g < 0.5 : g > 0.25)) || (in == 0 && !top && g > 0.7);
                if (on && c >= 1 && c < 23) return pick(AME, 2.2 + ripple(fr, 6) * 2.6);
            }
        }
        return pick(STONE, idx);
    };
    static Mat OBSIDIAN = (e, p, w, f, fr) -> {
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 3);
        if (n > 0.93) return 0x3A2168;
        return n < 0.4 ? OBS[0] : n < 0.75 ? OBS[1] : n < 0.9 ? OBS[2] : OBS[3];
    };
    /** Walknochen der Stimmgabel: Wachstumsringe quer zum Zinken */
    static Mat FORK = (e, p, w, f, fr) -> {
        double n = hash(q(p[0]), q(p[1]) + 9, q(p[2]) + f * 17);
        double idx = 3.1 + (n - 0.5) * 0.9 + (f == U ? 0.9 : f == D ? -1.4 : 0);
        if (f != U && f != D && Math.floorMod(q(p[1]), 4) == 0) idx -= 0.8;
        if (f != U && f != D && e.to[1] - p[1] < 0.5) idx += 0.6;
        return pick(BONE, idx);
    };
    static Mat STAR_M = (e, p, w, f, fr) -> {
        double d = Math.max(Math.abs(w[0] - 8), Math.max(Math.abs(w[1] - 10.25), Math.abs(w[2] - 8)));
        double t = 0.5 + 0.5 * Math.sin(2 * Math.PI * fr / FRAMES);
        return pick(GLOW, 6.4 - d * 1.5 + t * 0.9 + hash(q(p[0]), q(p[1]), q(p[2]) + f) * 0.8);
    };

    static List<El> altar() {
        List<El> m = new ArrayList<>();
        m.add(new El("base", 2, 0, 2, 14, 2, 14, BLACKSTONE));
        m.add(new El("step", 3.5, 2, 3.5, 12.5, 3.5, 12.5, BLACKSTONE));
        // leuchtende Rillen: äußerer Ring auf dem Sockel, innerer auf der Stufe, Adern am Schaft
        frame(m, "groove_outer", 2.75, 13.25, 2.0, 2.1, 0.3, rippleMat(4));
        frame(m, "groove_inner", 4.5, 11.5, 3.5, 3.6, 0.3, rippleMat(2));
        m.add(new El("stem", 6.25, 3.5, 6.25, 9.75, 6, 9.75, OBSIDIAN));
        m.add(new El("vein", 7.5, 3.6, 6.15, 8.5, 6, 6.25, rippleMat(0)).glow().noFaces(S, U, D));
        m.add(new El("vein", 7.5, 3.6, 9.75, 8.5, 6, 9.85, rippleMat(0)).glow().noFaces(N, U, D));
        m.add(new El("vein", 6.15, 3.6, 7.5, 6.25, 6, 8.5, rippleMat(0)).glow().noFaces(E, U, D));
        m.add(new El("vein", 9.75, 3.6, 7.5, 9.85, 6, 8.5, rippleMat(0)).glow().noFaces(W, U, D));
        // Stimmgabel aus Walknochen
        m.add(new El("yoke", 4, 6, 6.75, 12, 7.5, 9.25, FORK));
        m.add(new El("socket", 6.5, 7.5, 7.25, 9.5, 8, 8.75, FORK));
        for (int s = -1; s <= 1; s += 2) {
            m.add(tine(s, 2.5, 4.0, 7.5, 10.75, 2.0, "tine"));
            m.add(tine(s, 2.75, 3.75, 10.75, 12.25, 1.5, "tine_upper"));
            m.add(tine(s, 3.0, 3.6, 12.25, 13.0, 1.0, "tine_tip"));
            double xi = 8 + s * 2.5, xo = 8 + s * 2.4;
            m.add(new El("tine_inlay", Math.min(xi, xo), 8, 7.75, Math.max(xi, xo), 10.5, 8.25, rippleMat(6)).glow().noFaces(U, D, s < 0 ? W : E));
        }
        // Sternkristall: drei verdrehte Würfel + vier Strahlen
        double c = 10.25, h = 0.8;
        m.add(new El("star_core", 8 - h, c - h, 8 - h, 8 + h, c + h, 8 + h, STAR_M).glow().rotZ(45).at(8, c, 8));
        m.add(new El("star_core", 8 - h, c - h, 8 - h, 8 + h, c + h, 8 + h, STAR_M).glow().rotX(45).at(8, c, 8));
        m.add(new El("star_core", 8 - h, c - h, 8 - h, 8 + h, c + h, 8 + h, STAR_M).glow().rotY(45).at(8, c, 8));
        m.add(new El("star_ray", 7.75, 8.5, 7.75, 8.25, 12.0, 8.25, STAR_M).glow());
        m.add(new El("star_ray", 6.5, c - 0.25, 7.75, 9.5, c + 0.25, 8.25, STAR_M).glow());
        m.add(new El("star_ray", 7.75, c - 0.25, 6.5, 8.25, c + 0.25, 9.5, STAR_M).glow());
        return m;
    }
    /** vier flache Leisten als quadratischer Rahmen a..b */
    static void frame(List<El> m, String name, double a, double b, double y0, double y1, double wd, Mat mat) {
        m.add(new El(name, a, y0, a, b, y1, a + wd, mat).glow().noFaces(D));
        m.add(new El(name, a, y0, b - wd, b, y1, b, mat).glow().noFaces(D));
        m.add(new El(name, a, y0, a + wd, a + wd, y1, b - wd, mat).glow().noFaces(D, N, S));
        m.add(new El(name, b - wd, y0, a + wd, b, y1, b - wd, mat).glow().noFaces(D, N, S));
    }
    static El tine(int s, double r0, double r1, double y0, double y1, double depth, String name) {
        double xa = 8 + s * r0, xb = 8 + s * r1;
        return new El(name, Math.min(xa, xb), y0, 8 - depth / 2, Math.max(xa, xb), y1, 8 + depth / 2, FORK);
    }

    static void buildAltar(Path root, Path preview) throws Exception {
        List<El> model = altar();
        System.out.println("void_altar: " + model.size() + " Elemente");
        ATLAS = 128;
        BufferedImage[] atlas = atlas(model);
        List<Quad> qs = quads(model);
        if (preview != null) {
            Files.createDirectories(preview);
            int P = 420;
            BufferedImage sheet = new BufferedImage(P * 4 + 50, P + 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(new Color(0x59636E)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            g.setColor(new Color(0x14121A)); g.fillRect(P * 3 + 35, 0, P + 15, P + 20);
            // GUI-Ansicht (block/block: [30,225,0]), von vorn, von der Seite, GUI-Ansicht Frame 4
            double[][][] views = {mul(Rx(30), Ry(225)), mul(Rx(12), Ry(0)), mul(Rx(12), Ry(90)), mul(Rx(30), Ry(200))};
            for (int v = 0; v < 4; v++) {
                double[] ft = fit(qs, views[v], P, P, 12);
                big(g, img(render(qs, atlas[v == 3 ? 4 : 0], views[v], ft, P, P, 1, false), P, P), 10 + v * (P + 10), 10, 1);
            }
            g.dispose();
            ImageIO.write(sheet, "png", preview.resolve("void_altar_sheet.png").toFile());
            ImageIO.write(atlas[0], "png", preview.resolve("void_altar_atlas.png").toFile());
            System.out.println("  Vorschau: " + preview.resolve("void_altar_sheet.png"));
            return;
        }
        Path assets = root.resolve("src/main/resources/assets/hha_void");
        String head = "  \"parent\": \"minecraft:block/block\",\n  \"ambientocclusion\": false,\n";
        Files.writeString(assets.resolve("models/block/void_altar.json"), json(model, "hha_void:block/void_altar", "hha_void:block/void_altar", null, head));
        ImageIO.write(strip(atlas), "png", assets.resolve("textures/block/void_altar.png").toFile());
        Files.writeString(assets.resolve("textures/block/void_altar.png.mcmeta"), "{\"animation\":{\"frametime\":5,\"interpolate\":true}}\n");
        System.out.println("  geschrieben: models/block/void_altar.json, textures/block/void_altar.png");
    }

    // ------------------------------------------------------------------ Barren
    /** K Umriss, 1-7 dunkles Metall, a-h Violett-Rampe */
    static final String[] INGOT = {
        "................",
        "................",
        "................",
        "....KKKKKKKKKKK.",
        "...K6665555443K.",
        "..K6bcddccbbb3K.",
        ".K5bcdeedccbc23K",
        "K7766655544K223K",
        "K5544444433K222K",
        "K4c4333c332K12K.",
        "K43cc3333c2K1K..",
        "K3332222c22KK...",
        "KKKKKKKKKKKK....",
        "................",
        "................",
        "................",
    };
    static final String RAMP = "abcdefgh";
    static final Map<Character, Integer> INGOT_PAL = new HashMap<>();
    static {
        INGOT_PAL.put('K', OUTLINE);
        int[] metal = {0x15111B, 0x201A29, 0x2C2537, 0x3A3247, 0x4E455E, 0x675D79, 0x897E9B};
        for (int i = 0; i < 7; i++) INGOT_PAL.put((char) ('1' + i), metal[i]);
        int[] ramp = {0x1E0B45, 0x351370, 0x5220A6, 0x7834DD, 0x9B47FF, 0xCD53FF, 0xE796FF, 0xF9DCFF};
        for (int i = 0; i < 8; i++) INGOT_PAL.put(RAMP.charAt(i), ramp[i]);
    }
    static char shift(char ch, int by) {
        int i = RAMP.indexOf(ch);
        return i < 0 ? ch : RAMP.charAt(Math.max(0, Math.min(7, i + by)));
    }

    /** Frame t von 40: langsamer Atem-Puls, ein Glanzlicht wandert über die Lache, zwei Sterne funkeln */
    static BufferedImage ingotFrame(int t) {
        char[][] g = new char[16][];
        for (int y = 0; y < 16; y++) g[y] = INGOT[y].toCharArray();
        if (t >= 20 && t < 26) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) g[y][x] = shift(g[y][x], 1);
        int pq = t - 4;
        if (pq >= 0 && pq < 12) for (int y = 5; y <= 6; y++) for (int x = 0; x < 16; x++) {
            if (RAMP.indexOf(g[y][x]) < 1) continue;
            int k = x + (6 - y) - (2 + pq);
            if (k == 0) g[y][x] = 'g'; else if (k == -1 || k == 1) g[y][x] = shift(g[y][x], 1);
        }
        if (t >= 28 && t < 34) g[5][9] = t < 30 || t >= 32 ? 'f' : 'h';
        if (t >= 34 && t < 40) g[6][5] = t < 36 || t >= 38 ? 'g' : 'h';
        BufferedImage im = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            Integer c = INGOT_PAL.get(g[y][x]);
            if (c != null) im.setRGB(x, y, 0xFF000000 | c);
        }
        return im;
    }

    static void buildIngot(Path root, Path preview) throws Exception {
        List<BufferedImage> frames = new ArrayList<>();
        List<int[]> seq = new ArrayList<>();   // {Frame-Index, Dauer in 2-Tick-Schritten}
        for (int t = 0; t < 40; t++) {
            BufferedImage f = ingotFrame(t);
            int idx = -1;
            for (int i = 0; i < frames.size(); i++) if (same(frames.get(i), f)) { idx = i; break; }
            if (idx < 0) { idx = frames.size(); frames.add(f); }
            if (!seq.isEmpty() && seq.get(seq.size() - 1)[0] == idx) seq.get(seq.size() - 1)[1]++;
            else seq.add(new int[]{idx, 1});
        }
        System.out.println("void_ingot: " + frames.size() + " Frames");
        if (preview != null) {
            Files.createDirectories(preview);
            BufferedImage sheet = new BufferedImage(frames.size() * 136 + 10, 150, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(new Color(0x59636E)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            for (int i = 0; i < frames.size(); i++) big(g, frames.get(i), 10 + i * 136, 10, 8);
            g.dispose();
            ImageIO.write(sheet, "png", preview.resolve("void_ingot_sheet.png").toFile());
            return;
        }
        Path tex = root.resolve("src/main/resources/assets/hha_void/textures/item");
        ImageIO.write(strip(frames.toArray(new BufferedImage[0])), "png", tex.resolve("void_ingot.png").toFile());
        StringBuilder b = new StringBuilder("{\"animation\":{\"interpolate\":false,\"frametime\":2,\"frames\":[");
        for (int i = 0; i < seq.size(); i++) {
            int[] e = seq.get(i);
            b.append(i > 0 ? "," : "").append(e[1] == 1 ? "" + e[0] : "{\"index\":" + e[0] + ",\"time\":" + (e[1] * 2) + "}");
        }
        Files.writeString(tex.resolve("void_ingot.png.mcmeta"), b.append("]}}\n").toString());
        System.out.println("  geschrieben: textures/item/void_ingot.png (+mcmeta)");
    }
    static boolean same(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
        return true;
    }

    // ------------------------------------------------------------------ Hand-Vorschau (--hand <dir>)
    // Rechnet nach, was das Spiel mit den Display-Werten macht, und rendert die Waffe so, wie sie in der Ego-Sicht
    // (rechte + linke Hand, 854x480, FOV 70) und am Spielermodell (Seite / vorn / F5 von hinten) erscheint.
    // Damit lassen sich Display-Werte in Sekunden ausprobieren statt mit einem Spielstart je Versuch.
    //   Ego-Sicht:   Kamera im Ursprung, Blick -z;  p_cam = T(±0.56, -0.52, -0.72) * Display * p
    //   3rd Person:  Spieler = scale(-1,-1,1) * 0.9375 * T(0,-1.501,0); Arm-Drehpunkt (-5,2,0)/16, Arm um -18° nach
    //                vorn; Item = Arm * Rx(-90) * Ry(180) * T(1/16, 0.125, -0.625) * Display * p
    //   Display:     T(translation/16) * Rx*Ry*Rz * scale * T(-0.5) * p/16   (linke Hand: x-Verschiebung, y-/z-Rotation gespiegelt)
    // Varianten zum Vergleichen per System-Property, mehrere durch ';' getrennt, je "rx,ry,rz,tx,ty,tz,s":
    //   java -Dvoid_sword.fp="..." -Dvoid_mace.fp="..." -Dvoid_sword.tp="..." -Dvoid_mace.tp="..." tools/textures/GenVoidModels.java . --hand <dir>
    static double[][] rotXYZ(double[] r) { return mul(Rx(r[0]), mul(Ry(r[1]), Rz(r[2]))); }

    /** affine Abbildung p -> m*p + o */
    static class Xf {
        final double[][] m; final double[] o;
        Xf(double[][] m, double[] o) { this.m = m; this.o = o; }
        static Xf move(double x, double y, double z) { return new Xf(Rz(0), new double[]{x, y, z}); }
        static Xf rot(double[][] r) { return new Xf(r, new double[3]); }
        static Xf scale(double x, double y, double z) { return new Xf(new double[][]{{x, 0, 0}, {0, y, 0}, {0, 0, z}}, new double[3]); }
        double[] at(double[] p) { double[] v = mul(m, p); return new double[]{v[0] + o[0], v[1] + o[1], v[2] + o[2]}; }
        /** Verkettung: die letzte Abbildung wird zuerst angewendet (wie ein MatrixStack) */
        static Xf chain(Xf... x) {
            Xf r = x[x.length - 1];
            for (int i = x.length - 2; i >= 0; i--) r = new Xf(mul(x[i].m, r.m), x[i].at(r.o));
            return r;
        }
    }
    /** Display-Eintrag {rx,ry,rz,tx,ty,tz,s} als Abbildung von Modell-Einheiten (0..16) in Blöcke; left: so wie das Spiel den Eintrag der linken Hand anwendet */
    static Xf displayXf(double[] d, boolean left) {
        int i = left ? -1 : 1;
        return Xf.chain(Xf.move(i * d[3] / 16, d[4] / 16, d[5] / 16), Xf.rot(rotXYZ(new double[]{d[0], i * d[1], i * d[2]})),
            Xf.scale(d[6], d[6], d[6]), Xf.move(-0.5, -0.5, -0.5), Xf.scale(1 / 16.0, 1 / 16.0, 1 / 16.0));
    }
    static List<Quad> place(List<Quad> qs, Xf x) {
        List<Quad> out = new ArrayList<>();
        for (Quad s : qs) {
            Quad qd = new Quad();
            qd.a = x.at(s.a); qd.b = x.at(s.b); qd.d = x.at(s.d);
            double[] n = mul(x.m, s.n); double l = Math.sqrt(sq(n[0]) + sq(n[1]) + sq(n[2]));
            qd.n = new double[]{n[0] / l, n[1] / l, n[2] / l};
            qd.uv = s.uv; qd.emissive = s.emissive; qd.fx = s.fx; qd.flat = s.flat;
            out.add(qd);
        }
        return out;
    }
    static void box(List<Quad> out, Xf x, double x1, double y1, double z1, double x2, double y2, double z2, int col) {
        El e = new El("box", x1, y1, z1, x2, y2, z2, null);
        double[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        List<Quad> qs = new ArrayList<>();
        for (int f = 0; f < 6; f++) {
            double[][] c = e.corners(f);
            Quad qd = new Quad(); qd.a = c[0]; qd.b = c[1]; qd.d = c[2]; qd.n = normals[f]; qd.flat = 0xFF000000 | col;
            qs.add(qd);
        }
        out.addAll(place(qs, x));
    }
    static Xf lookAt(double[] eye, double[] target) {
        double[] f = {target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]};
        double l = Math.sqrt(sq(f[0]) + sq(f[1]) + sq(f[2]));
        for (int k = 0; k < 3; k++) f[k] /= l;
        double[] r = {-f[2], 0, f[0]};                                         // f x (0,1,0)
        l = Math.hypot(r[0], r[2]); r[0] /= l; r[2] /= l;
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        double[][] m = {r, u, {-f[0], -f[1], -f[2]}};
        double[] o = mul(m, eye);
        return new Xf(m, new double[]{-o[0], -o[1], -o[2]});
    }
    /** perspektivischer Render von Quads im Kameraraum (Blick -z) auf [img] */
    static void renderPersp(List<Quad> qs, BufferedImage tex, BufferedImage img, double fovDeg) {
        int w = img.getWidth(), h = img.getHeight(), n = qs.size();
        double tan = Math.tan(Math.toRadians(fovDeg) / 2), aspect = (double) w / h;
        double[] L = {-0.30, 0.80, 0.52};
        double[][] U = new double[n][], V = new double[n][], Nn = new double[n][];
        double[] uu = new double[n], vv = new double[n], an = new double[n], shade = new double[n];
        for (int i = 0; i < n; i++) {
            Quad qd = qs.get(i);
            U[i] = new double[]{qd.b[0] - qd.a[0], qd.b[1] - qd.a[1], qd.b[2] - qd.a[2]};
            V[i] = new double[]{qd.d[0] - qd.a[0], qd.d[1] - qd.a[1], qd.d[2] - qd.a[2]};
            Nn[i] = new double[]{U[i][1] * V[i][2] - U[i][2] * V[i][1], U[i][2] * V[i][0] - U[i][0] * V[i][2], U[i][0] * V[i][1] - U[i][1] * V[i][0]};
            uu[i] = sq(U[i][0]) + sq(U[i][1]) + sq(U[i][2]); vv[i] = sq(V[i][0]) + sq(V[i][1]) + sq(V[i][2]);
            an[i] = qd.a[0] * Nn[i][0] + qd.a[1] * Nn[i][1] + qd.a[2] * Nn[i][2];
            shade[i] = qd.emissive ? 1 : 0.55 + 0.45 * Math.max(0, qd.n[0] * L[0] + qd.n[1] * L[1] + qd.n[2] * L[2]);
        }
        for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
            double dx = (2 * (px + 0.5) / w - 1) * tan * aspect, dy = (1 - 2 * (py + 0.5) / h) * tan, dz = -1;
            double bestK = 1e9; int best = 0;
            for (int i = 0; i < n; i++) {
                double dn = dx * Nn[i][0] + dy * Nn[i][1] + dz * Nn[i][2];
                if (Math.abs(dn) < 1e-12) continue;
                double k = an[i] / dn;
                if (k < 0.05 || k >= bestK) continue;
                Quad qd = qs.get(i);
                double rx = k * dx - qd.a[0], ry = k * dy - qd.a[1], rz = k * dz - qd.a[2];
                double s = (rx * U[i][0] + ry * U[i][1] + rz * U[i][2]) / uu[i], t = (rx * V[i][0] + ry * V[i][1] + rz * V[i][2]) / vv[i];
                if (s < 0 || s >= 1 || t < 0 || t >= 1) continue;
                int c;
                if (qd.flat != 0) c = qd.flat;
                else {
                    int[] r = qd.uv;
                    c = tex.getRGB(r[0] + Math.min(r[2] - 1, (int) (s * r[2])), r[1] + Math.min(r[3] - 1, (int) (t * r[3])));
                    if ((c >>> 24) == 0) continue;
                }
                bestK = k;
                double sh = shade[i];
                best = 0xFF000000 | ((int) (((c >> 16) & 255) * sh) << 16) | ((int) (((c >> 8) & 255) * sh) << 8) | (int) ((c & 255) * sh);
            }
            if (bestK < 1e8) img.setRGB(px, py, best);
        }
    }
    static BufferedImage backdrop(int w, int h, boolean hud) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setColor(new Color(0x9DBEF5)); g.fillRect(0, 0, w, h / 2);
        g.setColor(new Color(0x4B4F58)); g.fillRect(0, h / 2, w, h - h / 2);
        if (hud) {
            g.setColor(Color.WHITE); g.fillRect(w / 2 - 5, h / 2, 11, 1); g.fillRect(w / 2, h / 2 - 5, 1, 11);
            g.setColor(new Color(0x30000000, true)); g.fillRect(w / 2 - 182, h - 44, 364, 44);      // Hotbar
        }
        g.dispose();
        return im;
    }
    /** Ego-Sicht mit Display-Werten d */
    static BufferedImage firstPerson(List<Quad> qs, BufferedImage tex, double[] d, boolean left) {
        BufferedImage im = backdrop(854, 480, true);
        renderPersp(place(qs, Xf.chain(Xf.move(left ? -0.56 : 0.56, -0.52, -0.72), displayXf(d, left))), tex, im, 70);
        return im;
    }
    /** Spielermodell (Kästen) mit der Waffe in der rechten Hand; Welt: Spieler blickt nach -z, Füße auf y=0 */
    static List<Quad> rig(List<Quad> item, double[] d) {
        List<Quad> out = new ArrayList<>();
        Xf body = Xf.chain(Xf.scale(-0.9375, -0.9375, 0.9375), Xf.move(0, -1.501, 0)), px = Xf.scale(1 / 16.0, 1 / 16.0, 1 / 16.0);
        Xf arm = Xf.chain(body, Xf.move(-5 / 16.0, 2 / 16.0, 0), Xf.rot(Rx(-18)));
        Xf armL = Xf.chain(body, Xf.move(5 / 16.0, 2 / 16.0, 0));
        box(out, Xf.chain(body, px), -4, -8, -4, 4, 0, 4, 0xB08A70);
        box(out, Xf.chain(body, px), -4, 0, -2, 4, 12, 2, 0x3C7A8C);
        box(out, Xf.chain(arm, px), -3, -2, -2, 1, 10, 2, 0x9A7860);
        box(out, Xf.chain(armL, px), -1, -2, -2, 3, 10, 2, 0x9A7860);
        box(out, Xf.chain(body, px), -3.9, 12, -2, 0.1, 24, 2, 0x34407A);
        box(out, Xf.chain(body, px), -0.1, 12, -2, 3.9, 24, 2, 0x2C3668);
        box(out, Xf.move(0, 0, 0), -8, -0.2, -8, 8, 0, 8, 0x4B4F58);
        out.addAll(place(item, Xf.chain(arm, Xf.rot(Rx(-90)), Xf.rot(Ry(180)), Xf.move(1 / 16.0, 0.125, -0.625), displayXf(d, false))));
        return out;
    }
    /** Varianten aus der System-Property; Kurzform (mit "p" davor) wird über [shortForm] in Display-Werte umgerechnet */
    static double[][] variants(String prop, double[] dflt, java.util.function.UnaryOperator<double[]> shortForm) {
        String s = System.getProperty(prop);
        if (s == null || s.isBlank()) return new double[][]{dflt};
        String[] parts = s.split(";");
        double[][] out = new double[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            boolean sf = p.startsWith("p");
            double[] v = Arrays.stream((sf ? p.substring(1) : p).trim().split(",")).mapToDouble(Double::parseDouble).toArray();
            out[i] = sf ? shortForm.apply(v) : v;
            if (sf) System.out.println("  " + prop + " " + p + " -> " + vec(out[i]));
        }
        return out;
    }
    static void handPreview(Path dir, String name, List<El> model, double[] fp, double[] tp, java.util.function.UnaryOperator<double[]> fpShort, java.util.function.UnaryOperator<double[]> tpShort) throws Exception {
        Files.createDirectories(dir);
        BufferedImage tex = atlas(model)[0];
        List<Quad> qs = quads(model);
        double[][] fps = variants(name + ".fp", fp, fpShort), tps = variants(name + ".tp", tp, tpShort);
        int W = 854, H = 480;
        BufferedImage sheet = new BufferedImage(W * 2 + 10, (H + 10) * fps.length, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        for (int i = 0; i < fps.length; i++) {
            g.drawImage(firstPerson(qs, tex, fps[i], false), 0, i * (H + 10), null);
            g.drawImage(firstPerson(qs, tex, leftOf(fps[i]), true), W + 10, i * (H + 10), null);
            g.setColor(Color.YELLOW); g.drawString(name + " fp " + vec(fps[i]), 6, i * (H + 10) + 14);
        }
        g.dispose();
        ImageIO.write(sheet, "png", dir.resolve(name + "_hand_1st.png").toFile());
        int P = 420;
        // Seite (rechts), vorn, F5 von hinten (4 Blöcke hinter den Augen, FOV 70)
        double[][][] cams = {{{7, 1.3, -0.6}, {0, 1.3, -0.6}}, {{-1.5, 1.5, -7}, {0, 1.2, 0}}, {{0, 1.62, 4}, {0, 1.62, 0}}};
        double[] fov = {30, 30, 70};
        sheet = new BufferedImage((P + 10) * 3, (P + 10) * tps.length, BufferedImage.TYPE_INT_ARGB);
        g = sheet.createGraphics();
        for (int i = 0; i < tps.length; i++) {
            List<Quad> world = rig(qs, tps[i]);
            for (int v = 0; v < 3; v++) {
                BufferedImage im = backdrop(P, P, false);
                renderPersp(place(world, lookAt(cams[v][0], cams[v][1])), tex, im, fov[v]);
                g.drawImage(im, v * (P + 10), i * (P + 10), null);
            }
            g.setColor(Color.YELLOW); g.drawString(name + " tp " + vec(tps[i]), 6, i * (P + 10) + 14);
        }
        g.dispose();
        ImageIO.write(sheet, "png", dir.resolve(name + "_hand_3rd.png").toFile());
        System.out.println("  Hand-Vorschau: " + dir.resolve(name + "_hand_1st.png") + ", " + name + "_hand_3rd.png");
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path preview = args.length > 2 && args[1].equals("--preview") ? Path.of(args[2]) : null;
        ATLAS = 128;
        if (args.length > 2 && args[1].equals("--hand")) {
            Path dir = Path.of(args[2]);
            // Kurzformen: Ego-Sicht "p lean,pitch,roll,handX,handY,handZ,Größe" (siehe pose), 3rd Person "p z,Größe,GriffY" (siehe held)
            handPreview(dir, "void_sword", sword(), SWORD_FP, SWORD_TP, v -> pose(v[0], v[1], v[2], new double[]{v[3], v[4], v[5]}, v[6], SWORD_GRIP),
                v -> held(55, new double[]{0, 4, 0.5}, 0.85, SWORD_GRIP_VANILLA, new double[]{0, -90, v[0]}, v[1], new double[]{8, v[2], 8}));
            handPreview(dir, "void_mace", mace(), MACE_FP, MACE_TP, v -> pose(v[0], v[1], v[2], new double[]{v[3], v[4], v[5]}, v[6], MACE_GRIP),
                v -> held(55, new double[]{0, 4, 1}, 1, MACE_GRIP_VANILLA, new double[]{0, -90, v[0]}, v[1], new double[]{8, v[2], 8}));
            return;
        }
        build(root, preview, "void_sword", sword(), display(SWORD_TP, SWORD_FP), 205, 15, 3, 3);
        build(root, preview, "void_mace", mace(), display(MACE_TP, MACE_FP), 25, 18, 4, 4);
        buildAltar(root, preview);
        buildIngot(root, preview);
    }
}
