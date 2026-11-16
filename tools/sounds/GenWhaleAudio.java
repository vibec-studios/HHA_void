import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Alle Klänge für Oruun, den Sternenwal (HHA_void) – komplett synthetisiert, keine Samples, deterministisch
 * (jeder Klang hat einen festen Zufalls-Startwert).
 *
 * ANLEITUNG (aus dem Projektordner, JDK 21+ genügt, keine weiteren Abhängigkeiten):
 *
 *   java tools/sounds/GenWhaleAudio.java <pfad/zu/ffmpeg.exe> [name,name,...]
 *
 *   - ffmpeg (portable, nur entpacken): https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip
 *   - ohne Namensliste wird alles erzeugt; sonst nur die genannten (z. B. "whale_phase1,song1,debris2").
 *   - WAVs landen in build/whale_audio/, OGGs direkt unter src/main/resources/assets/hha_void/sounds/...
 *   - Danach wird jede OGG mit ffmpeg zurückdekodiert und geprüft (Spitze, Lautheit, Loop-Naht, Spektrum);
 *     der Bericht steht in build/whale_audio/report.txt.
 *
 * Musik (Stereo, nahtlose Loops: alles wird in einen Ringpuffer genau der Looplänge gemischt, Hall-Ausklänge
 * laufen über die Naht zurück an den Anfang, Hall/EQ/Kompressor/Limiter laufen zweimal über den Ring):
 *   E-Moll (äolisch), Leitmotiv „Oruuns Ruf“ (E–H–A–G | G–D–C–H | A–G–Fis–E, fällt am Ende auf H ab).
 *   whale_phase1  60 BPM, 32 Takte: tiefe warme Flächen, langsamer Herzschlag-Puls, das Motiv als Walgesang.
 *   whale_phase2  90 BPM, 48 Takte: Streicher-Ostinato (3+3+2), Taiko/Pauken, Motiv in Wal + Hörnern.
 *   whale_phase3  120 BPM, 64 Takte: Chor, 16tel-Ostinato, schwere Trommeln, Motiv im vollen Satz + Augmentation.
 *   whale_victory 64 BPM, ~40 s: Motiv in E-Dur, verklingt (kein Loop).
 *   ambient_deep  64 s Loop: Wind der Leere, ferner Walgesang, leises Sternenglitzern, tiefer Grundton.
 * Effekte (Mono, damit sie im Spiel positional sind): tief, voll, oberhalb ~6 kHz bewusst gedämpft.
 */
public class GenWhaleAudio {
    static final int SR = 44100;
    static final double TAU = 2 * Math.PI;

    static double mtof(double m) { return 440.0 * Math.pow(2, (m - 69) / 12.0); }
    static double clamp(double x, double a, double b) { return x < a ? a : (x > b ? b : x); }
    static double smooth(double x) { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); }
    static double sq(double x) { return x * x; }
    static int ns(double s) { return (int) Math.round(s * SR); }
    static double db2g(double d) { return Math.pow(10, d / 20); }
    static double g2db(double g) { return 20 * Math.log10(Math.max(g, 1e-12)); }

    // ================================================================================== Grundbausteine

    /** Stützstellen-Kurve (Zeit, Wert) mit Kosinus-Übergängen – für gleitende Tonhöhen, Hüllkurven usw. */
    static final class Curve {
        final double[] t, v; int last = 1;
        Curve(double... tv) {
            int n = tv.length / 2; t = new double[n]; v = new double[n];
            for (int i = 0; i < n; i++) { t[i] = tv[2 * i]; v[i] = tv[2 * i + 1]; }
            for (int i = 1; i < n; i++) if (t[i] <= t[i - 1]) t[i] = t[i - 1] + 1e-4;
        }
        Curve(List<double[]> pts) {
            int n = pts.size(); t = new double[n]; v = new double[n];
            for (int i = 0; i < n; i++) { t[i] = pts.get(i)[0]; v[i] = pts.get(i)[1]; }
            for (int i = 1; i < n; i++) if (t[i] <= t[i - 1]) t[i] = t[i - 1] + 1e-4;
        }
        double at(double x) {
            int n = t.length;
            if (n == 1 || x <= t[0]) return v[0];
            if (x >= t[n - 1]) return v[n - 1];
            if (last < 1 || last >= n || t[last - 1] > x) last = 1;
            while (t[last] < x) last++;
            double u = (x - t[last - 1]) / (t[last] - t[last - 1]);
            u = 0.5 - 0.5 * Math.cos(Math.PI * u);
            return v[last - 1] + (v[last] - v[last - 1]) * u;
        }
        double end() { return t[t.length - 1]; }
    }

    /** schneller Rauschgenerator (xorshift), gleichverteilt in [-1, 1] */
    static final class Noise {
        long s;
        Noise(long seed) { s = seed * 0x9E3779B97F4A7C15L + 0x1234567L; if (s == 0) s = 1; }
        double next() { s ^= s << 13; s ^= s >>> 7; s ^= s << 17; return ((s >>> 11) * (1.0 / 9007199254740992.0)) * 2 - 1; }
    }

    /** langsam wandernder Zufallswert in [-1, 1] (Drift, LFO) */
    static final class Wander {
        final Random r; final double rate; double a, b, ph;
        Wander(Random r, double rate) { this.r = r; this.rate = rate; a = r.nextDouble() * 2 - 1; b = r.nextDouble() * 2 - 1; ph = r.nextDouble(); }
        double next() {
            ph += rate / SR;
            if (ph >= 1) { ph -= 1; a = b; b = r.nextDouble() * 2 - 1; }
            return a + (b - a) * (0.5 - 0.5 * Math.cos(Math.PI * ph));
        }
    }

    /** Biquad (RBJ), transponierte Direktform II */
    static final class Bq {
        double b0, b1, b2, a1, a2, z1, z2;
        Bq(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0;
        }
        double p(double x) { double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
        static Bq lp(double f, double q) {
            double w = TAU * f / SR, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Bq((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
        }
        static Bq hp(double f, double q) {
            double w = TAU * f / SR, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Bq((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
        }
        static Bq peak(double f, double q, double db) {
            double A = Math.pow(10, db / 40), w = TAU * f / SR, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Bq(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
        }
        static Bq shelf(double f, double db, boolean high) {
            double A = Math.pow(10, db / 40), w = TAU * f / SR, c = Math.cos(w), s = Math.sin(w), al = s / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * al;
            if (high) return new Bq(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
                    (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa);
            return new Bq(A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sa),
                    (A + 1) + (A - 1) * c + sa, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa);
        }
    }

    /** Zustandsvariablen-Filter (TPT/Simper), zeitvariabel: lp/bp/hp; bpn = Bandpass mit Spitzenverstärkung 1 */
    static final class Svf {
        double ic1, ic2, k = 1.4, a1, a2, a3, lp, bp, hp;
        Svf set(double fc, double q) {
            double g = Math.tan(Math.PI * clamp(fc, 10, SR * 0.45) / SR);
            k = 1 / q; a1 = 1 / (1 + g * (g + k)); a2 = g * a1; a3 = g * a2;
            return this;
        }
        void p(double v0) {
            double v3 = v0 - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2; lp = v2; bp = v1; hp = v0 - k * v1 - v2;
        }
        double bpn() { return bp * k; }
    }

    static double blep(double t, double dt) {
        if (t < dt) { t /= dt; return t + t - t * t - 1; }
        if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; }
        return 0;
    }
    /** bandbegrenzter Sägezahn (PolyBLEP) */
    static final class Saw {
        double ph;
        Saw(double ph) { this.ph = ph; }
        double next(double f) { double dt = f / SR, t = ph; ph += dt; if (ph >= 1) ph -= 1; return 2 * t - 1 - blep(t, dt); }
    }

    /** weicher Anstieg (att), halten bis dur, Kosinus-Ausklang (rel) */
    static double env(double t, double att, double dur, double rel) {
        if (t < 0) return 0;
        double a = att > 0 ? smooth(t / att) : 1;
        if (t <= dur) return a;
        double u = (t - dur) / Math.max(rel, 1e-4);
        if (u >= 1) return 0;
        return a * (0.5 + 0.5 * Math.cos(Math.PI * u));
    }
    /** Hüllkurve für Anschläge: linearer Anstieg (att, ganz kurz), exponentielles Abklingen (tau) */
    static double hit(double t, double att, double tau) { if (t < 0) return 0; return (t < att ? t / att : 1) * Math.exp(-Math.max(0, t - att) / tau); }

    static void normPeak(float[] x, double to) {
        double p = 0; for (float v : x) p = Math.max(p, Math.abs(v));
        if (p < 1e-9) return;
        float g = (float) (to / p); for (int i = 0; i < x.length; i++) x[i] *= g;
    }
    static float[] add(float[] a, float[] b, double gb, double offSec) {
        int off = ns(offSec), n = Math.max(a.length, off + b.length);
        float[] o = Arrays.copyOf(a, n);
        for (int i = 0; i < b.length; i++) if (off + i >= 0) o[off + i] += (float) (b[i] * gb);
        return o;
    }

    // ================================================================================== Instrumente

    /** Warme Fläche: 3 verstimmte Sägezähne pro Seite durch einen sanft atmenden Tiefpass. */
    static float[][] pad(Random r, double f, double dur, double vel, double fc, double att, double rel, double det) {
        int n = ns(dur + rel) + 1;
        float[] L = new float[n], R = new float[n];
        int V = 3;
        Saw[] sl = new Saw[V], sr = new Saw[V];
        double[] dl = new double[V], dr = new double[V];
        for (int v = 0; v < V; v++) {
            sl[v] = new Saw(r.nextDouble()); sr[v] = new Saw(r.nextDouble());
            double c = (v - 1) * det;
            dl[v] = Math.pow(2, (c + r.nextGaussian() * 1.5) / 1200); dr[v] = Math.pow(2, (-c + r.nextGaussian() * 1.5) / 1200);
        }
        Svf fl = new Svf(), fr = new Svf(), fl2 = new Svf(), fr2 = new Svf();
        Wander w = new Wander(r, 0.12), wa = new Wander(r, 0.2);
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR, e = env(t, att, dur, rel);
            double wv = w.next(), av = wa.next();
            if ((i & 15) == 0) {
                double c = fc * (0.55 + 0.45 * e) * (1 + 0.22 * wv);
                fl.set(c, 0.75); fr.set(c * 1.04, 0.75); fl2.set(c * 1.3, 0.6); fr2.set(c * 1.3, 0.6);
            }
            double xl = 0, xr = 0;
            for (int v = 0; v < V; v++) { xl += sl[v].next(f * dl[v]); xr += sr[v].next(f * dr[v]); }
            fl.p(xl / V); fr.p(xr / V); fl2.p(fl.lp); fr2.p(fr.lp);
            double a = e * vel * (1 + 0.12 * av);
            L[i] = (float) (fl2.lp * a); R[i] = (float) (fr2.lp * a);
        }
        return new float[][]{L, R};
    }

    static final double[][][] VOW = { // {Frequenz, Bandbreite, Verstärkung} × 3: 0 = „a“, 1 = „o“, 2 = „u“
            {{700, 100, 1}, {1150, 120, 0.5}, {2600, 170, 0.09}},
            {{450, 80, 1}, {800, 100, 0.42}, {2700, 170, 0.06}},
            {{330, 70, 1}, {700, 90, 0.22}, {2500, 160, 0.035}}};

    /** Chor: 3 Stimmen pro Seite, Vibrato, Formantfilter (Vokal a/o/u). */
    static float[][] choir(Random r, double f, double dur, double vel, int vow, double att, double rel) {
        int n = ns(dur + rel) + 1;
        float[][] out = new float[2][n];
        for (int side = 0; side < 2; side++) {
            int V = 3;
            Saw[] s = new Saw[V];
            double[] rate = new double[V], ph = new double[V], det = new double[V];
            Wander[] dr = new Wander[V];
            for (int v = 0; v < V; v++) {
                s[v] = new Saw(r.nextDouble()); rate[v] = 4.6 + r.nextDouble() * 1.0; ph[v] = r.nextDouble() * TAU;
                det[v] = (v - 1) * 7 + r.nextGaussian() * 3; dr[v] = new Wander(r, 0.8);
            }
            Svf[] fm = new Svf[3];
            for (int k = 0; k < 3; k++) fm[k] = new Svf().set(VOW[vow][k][0] * (side == 0 ? 1 : 1.02), VOW[vow][k][0] / VOW[vow][k][1]);
            Svf body = new Svf().set(Math.min(1800, f * 4), 0.7);
            Bq top = Bq.lp(3600, 0.7);
            float[] o = out[side];
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR, e = env(t, att, dur, rel), x = 0;
                double vd = 0.2 * smooth((t - 0.25) / 1.2);
                for (int v = 0; v < V; v++) {
                    ph[v] += TAU * rate[v] / SR;
                    double m = vd * Math.sin(ph[v]) + det[v] / 100 + 0.06 * dr[v].next();
                    x += s[v].next(f * Math.pow(2, m / 12));
                }
                x /= V;
                double y = 0;
                for (int k = 0; k < 3; k++) { fm[k].p(x); y += fm[k].bpn() * VOW[vow][k][2]; }
                body.p(x);
                y = y * 1.3 + body.lp * 0.25;
                o[i] = (float) (top.p(y) * e * vel);
            }
        }
        return out;
    }

    /** Tiefes Blech (Horn/Posaune): Sägezahn, Filter öffnet mit dem Anschwellen, leichte Sättigung. */
    static float[][] brass(Random r, double f, double dur, double vel, double att, double rel) {
        int n = ns(dur + rel) + 1;
        float[][] out = new float[2][n];
        for (int side = 0; side < 2; side++) {
            Saw a = new Saw(r.nextDouble()), b = new Saw(r.nextDouble());
            double da = Math.pow(2, (side == 0 ? -4 : 4) / 1200.0), db = Math.pow(2, (side == 0 ? 3 : -3) / 1200.0);
            Svf fl = new Svf();
            double vp = r.nextDouble() * TAU;
            float[] o = out[side];
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR, e = env(t, att, dur, rel);
                double sw = smooth(t / Math.max(att * 1.6, 0.05));
                vp += TAU * 5.0 / SR;
                double fm = f * Math.pow(2, 0.08 * Math.sin(vp) * smooth((t - 0.5) / 1.0) / 12);
                if ((i & 7) == 0) fl.set(Math.min(2600, fm * (1.3 + 4.2 * vel * sw * (0.7 + 0.3 * e))), 0.9);
                fl.p(0.5 * (a.next(fm * da) + b.next(fm * db)));
                double y = Math.tanh(fl.lp * 1.6) / 1.6;
                o[i] = (float) (y * e * vel);
            }
        }
        return out;
    }

    /** Gezupfte/gestoßene tiefe Streicher für Ostinati. */
    static float[] pluck(Random r, double f, double dur, double vel, double bright) {
        double tau = Math.max(0.06, dur * 0.55);
        int n = ns(dur + 0.12) + 1;
        float[] o = new float[n];
        Saw a = new Saw(r.nextDouble()), b = new Saw(r.nextDouble());
        double d = Math.pow(2, (5 + r.nextGaussian() * 2) / 1200.0);
        Svf fl = new Svf();
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double e = hit(t, 0.004, tau) * (t > dur ? 0.5 + 0.5 * Math.cos(Math.PI * Math.min(1, (t - dur) / 0.12)) : 1);
            if ((i & 7) == 0) fl.set(Math.min(3200, f * (1.6 + bright * 9 * Math.exp(-t / 0.07))), 0.85);
            fl.p(0.5 * (a.next(f) + b.next(f * d)));
            o[i] = (float) (fl.lp * e * vel);
        }
        return o;
    }

    /** Taiko/Odaiko: tiefe Membran mit Tonhöhen-Abfall, kurzer gedämpfter Schlägel. */
    static float[] taiko(Random r, double f0, double vel, double decay) {
        int n = ns(decay * 5 + 0.1);
        float[] o = new float[n];
        Noise nz = new Noise(r.nextLong());
        Svf sk = new Svf().set(900, 0.8);
        double p1 = 0, p2 = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f = f0 * (1 + 0.55 * Math.exp(-t / 0.028));
            p1 += TAU * f / SR; p2 += TAU * f * 1.59 / SR;
            double body = Math.sin(p1) * hit(t, 0.0015, decay) + 0.35 * Math.sin(p2) * hit(t, 0.0015, decay * 0.4);
            sk.p(nz.next());
            double skin = sk.lp * hit(t, 0.0008, 0.018) * 0.9;
            o[i] = (float) (Math.tanh((body + skin) * 1.4) / 1.2 * vel);
        }
        return o;
    }

    /** Pauke: inharmonische Kesselmoden, weicher Filzschlägel. */
    static float[] timpani(Random r, double f, double vel) {
        double[] ratio = {1, 1.504, 1.742, 2.0, 2.245, 2.494}, amp = {1, 0.55, 0.3, 0.28, 0.16, 0.1}, dec = {1.8, 1.1, 0.8, 0.7, 0.5, 0.4};
        int n = ns(3.2);
        float[] o = new float[n];
        Noise nz = new Noise(r.nextLong());
        Svf th = new Svf().set(500, 0.9);
        double[] ph = new double[ratio.length];
        for (int k = 0; k < ph.length; k++) ph[k] = r.nextDouble() * TAU;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR, bend = 1 + 0.025 * Math.exp(-t / 0.05), y = 0;
            for (int k = 0; k < ratio.length; k++) {
                ph[k] += TAU * f * ratio[k] * bend / SR;
                y += amp[k] * Math.sin(ph[k]) * hit(t, 0.002, dec[k]);
            }
            th.p(nz.next());
            y += th.lp * hit(t, 0.001, 0.02) * 1.2;
            o[i] = (float) (y * 0.45 * vel);
        }
        return o;
    }

    /** Sub-Schlag (Herzschlag/Puls, große Kino-Schläge). */
    static float[] thump(Random r, double fHi, double fLo, double vel, double decay, double drive) {
        int n = ns(decay * 5 + 0.05);
        float[] o = new float[n];
        double ph = 0;
        Noise nz = new Noise(r.nextLong());
        Svf lp = new Svf().set(180, 0.7);
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f = fLo + (fHi - fLo) * Math.exp(-t / 0.06);
            ph += TAU * f / SR;
            lp.p(nz.next());
            double y = Math.sin(ph) * hit(t, 0.006, decay) + lp.lp * hit(t, 0.003, 0.05) * 0.6;
            o[i] = (float) (Math.tanh(y * drive) / Math.tanh(drive) * vel);
        }
        return o;
    }

    /** Tiefer Halteton (Sinus + leise 2./3. Harmonische, damit er auch auf kleinen Lautsprechern trägt). */
    static float[] subNote(Random r, double f, double dur, double vel, double att, double rel) {
        int n = ns(dur + rel) + 1;
        float[] o = new float[n];
        double ph = r.nextDouble() * TAU;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            ph += TAU * f / SR;
            o[i] = (float) ((Math.sin(ph) + 0.28 * Math.sin(2 * ph + 0.3) + 0.08 * Math.sin(3 * ph)) * env(t, att, dur, rel) * vel);
        }
        return o;
    }

    /** Glocke/Sternenglitzern: weiche inharmonische Teiltöne, Obergrenze 7 kHz. */
    static float[] bell(Random r, double f, double vel, double decay) {
        double[] ratio = {1, 2.0, 2.76, 4.07, 5.4}, amp = {1, 0.3, 0.42, 0.16, 0.1}, dec = {1, 0.6, 0.45, 0.3, 0.2};
        int n = ns(decay * 4 + 0.05);
        float[] o = new float[n];
        double[] ph = new double[ratio.length];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR, y = 0;
            for (int k = 0; k < ratio.length; k++) {
                double fk = f * ratio[k];
                if (fk > 7000) continue;
                ph[k] += TAU * fk / SR;
                y += amp[k] * Math.sin(ph[k]) * hit(t, 0.004, decay * dec[k]);
            }
            o[i] = (float) (y * 0.6 * vel);
        }
        return o;
    }

    /** Gong/Tamtam-Anschwellen aus tiefen inharmonischen Teiltönen (statt Becken-Rauschen). */
    static float[] gong(Random r, double f0, double dur, double vel, boolean swell) {
        int K = 14, n = ns(dur) + 1;
        double[] fr = new double[K], am = new double[K], ph = new double[K], dc = new double[K];
        for (int k = 0; k < K; k++) {
            fr[k] = f0 * (1 + k * 0.62 + r.nextDouble() * 0.5); am[k] = 1.0 / (1 + k * 0.45);
            ph[k] = r.nextDouble() * TAU; dc[k] = dur * (0.9 - 0.5 * k / K);
        }
        float[] o = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR, y = 0;
            for (int k = 0; k < K; k++) {
                ph[k] += TAU * fr[k] * (1 + 0.002 * Math.sin(t * 1.3 + k)) / SR;
                double e = swell ? smooth(t / (dur * 0.85)) * (1 - smooth((t - dur * 0.85) / (dur * 0.15))) : hit(t, 0.01, dc[k] * 0.35);
                y += am[k] * Math.sin(ph[k]) * e;
            }
            o[i] = (float) (y * 0.22 * vel);
        }
        return o;
    }

    /** Wind der Leere: gefiltertes Rauschen mit wandernder Resonanz (tief, nicht zischig). */
    static float[] wind(Random r, double dur, double lo, double hi, double q) {
        int n = ns(dur) + 1;
        float[] o = new float[n];
        Noise nz = new Noise(r.nextLong());
        Wander w = new Wander(r, 0.09), w2 = new Wander(r, 0.23), g = new Wander(r, 0.07);
        Svf bp = new Svf(), lp = new Svf().set(1200, 0.6);
        for (int i = 0; i < n; i++) {
            if ((i & 31) == 0) bp.set(lo + (hi - lo) * (0.5 + 0.35 * w.next() + 0.15 * w2.next()), q);
            else { w.next(); w2.next(); }
            bp.p(nz.next());
            lp.p(bp.bpn());
            o[i] = (float) (lp.lp * (0.6 + 0.4 * g.next()));
        }
        return o;
    }

    // ---------------------------------------------------------------------------------------- Walstimme

    /** Parameter einer Walstimme: Tonhöhe (MIDI) und Lautstärke als Kurven über die Zeit. */
    static final class Voice {
        Curve pitch, amp, open, whistleH;
        double vib = 0.12, vibRate = 3.8, whistle = 0.5, growl = 0, growlRate = 15, sub = 0, breath = 0.02, tilt = 1.0,
                lpHz = 3000, jitter = 0.06, vibGrow = 0;
    }

    /**
     * Walstimme: additive Harmonische (Chebyshev-Rekursion, bandbegrenzt), Formanten (Maulöffnung „u“ → „a“),
     * eine schmale wandernde Resonanz, die einzelne Obertöne herauspfeift (der typische Walgesang),
     * Vibrato, Drift, optional Knurren (Unterton + Rauheit) und Atem (tiefpassgefiltertes Rauschen).
     */
    static float[] whale(Random r, Voice v) {
        double dur = Math.max(v.pitch.end(), v.amp.end()) + 0.05;
        int n = ns(dur);
        float[] o = new float[n];
        int K = 72;
        double[] a = new double[K + 1];
        double th = r.nextDouble() * TAU, thSub = 0, vibPh = r.nextDouble() * TAU, gp = 0;
        Wander drift = new Wander(r, 0.8), rough = new Wander(r, 7);
        Noise nz = new Noise(r.nextLong());
        Svf br = new Svf().set(650, 0.9), br2 = new Svf().set(1400, 0.6);
        int kmax = 1;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double vd = v.vib * (1 + v.vibGrow * t / dur) * smooth(t / 1.0);
            double m = v.pitch.at(t) + vd * Math.sin(vibPh) + v.jitter * drift.next();
            vibPh += TAU * v.vibRate / SR;
            double f0 = mtof(m);
            if ((i & 31) == 0) {
                double op = v.open == null ? 0.4 : v.open.at(t), wh = v.whistleH == null ? 0 : v.whistleH.at(t);
                double F1 = 260 + 420 * op, F2 = 640 + 620 * op, F3 = 2250;
                kmax = (int) Math.max(1, Math.min(K, v.lpHz * 1.7 / f0));
                for (int k = 1; k <= K; k++) {
                    if (k > kmax) { a[k] = 0; continue; }
                    double fk = k * f0;
                    double e = 1.0 / (1 + Math.pow(fk / 170.0, v.tilt))
                            * (0.3 + 0.5 / (1 + sq(fk / 150)) + 1.0 / (1 + sq((fk - F1) / 140)) + 0.55 / (1 + sq((fk - F2) / 200)) + 0.1 / (1 + sq((fk - F3) / 330)));
                    if (wh > 0) e += v.whistle * 0.22 / (1 + sq((k - wh) / 0.55));
                    e /= 1 + Math.pow(fk / v.lpHz, 4);
                    a[k] = e;
                }
            }
            double s1 = Math.sin(th), c2 = 2 * Math.cos(th), sp = 0, sc = s1, sum = a[1] * s1;
            for (int k = 2; k <= kmax; k++) { double sn = c2 * sc - sp; sp = sc; sc = sn; sum += a[k] * sn; }
            th += TAU * f0 / SR; if (th > TAU) th -= TAU;
            if (v.sub > 0) { thSub += TAU * f0 * 0.5 / SR; if (thSub > TAU) thSub -= TAU; sum += v.sub * a[1] * Math.sin(thSub); }
            double rg = 1;
            if (v.growl > 0) { gp += TAU * v.growlRate * (1 + 0.25 * rough.next()) / SR; rg = 1 - v.growl * (0.5 + 0.5 * Math.sin(gp)); }
            double am = v.amp.at(t), x = nz.next();
            br.p(x); br2.p(br.bpn());
            o[i] = (float) (am * (sum * rg + v.breath * 6 * br2.lp));
        }
        normPeak(o, 1);
        return o;
    }

    // ================================================================================== Harmonik / Motiv

    static final Map<String, int[]> CH = new HashMap<>(); // {Grundton-Tonklasse, Intervalle...}
    static void ch(String n, int... v) { CH.put(n, v); }
    static {
        ch("Em", 4, 0, 3, 7); ch("E", 4, 0, 4, 7); ch("C", 0, 0, 4, 7); ch("Cmaj7", 0, 0, 4, 7, 11); ch("G", 7, 0, 4, 7);
        ch("D", 2, 0, 4, 7); ch("Am", 9, 0, 3, 7); ch("Bsus", 11, 0, 5, 7); ch("B", 11, 0, 4, 7); ch("B7", 11, 0, 4, 7, 10);
        ch("F", 5, 0, 4, 7); ch("A", 9, 0, 4, 7); ch("C#m", 1, 0, 3, 7); ch("Em9", 4, 0, 3, 7, 2); ch("Emaj9", 4, 0, 4, 7, 11, 6);
        ch("Asus2", 9, 0, 2, 7);
    }
    static int[] chd(String c) { int[] v = CH.get(c); if (v == null) throw new IllegalArgumentException("Akkord " + c); return v; }
    static boolean inCh(String c, int m) {
        int[] v = chd(c); int pc = Math.floorMod(m - v[0], 12);
        for (int i = 1; i < v.length; i++) if (Math.floorMod(v[i], 12) == pc) return true;
        return false;
    }
    static int rootIn(String c, int lo) { int p = lo; while (Math.floorMod(p, 12) != chd(c)[0]) p++; return p; }

    /** Stimmführung: jede Stimme so nah wie möglich an der vorigen, Grundton/Terz/Quinte möglichst vorhanden. */
    static int[] voicing(String c, int lo, int hi, int[] prev, int nv) {
        List<Integer> cand = new ArrayList<>();
        for (int p = lo; p <= hi; p++) if (inCh(c, p)) cand.add(p);
        int[] v = new int[nv];
        boolean[] used = new boolean[cand.size()];
        for (int k = 0; k < nv; k++) {
            double tgt = prev == null ? lo + (hi - lo) * (k + 0.5) / nv : prev[Math.min(k, prev.length - 1)];
            int best = -1;
            for (int j = 0; j < cand.size(); j++) if (!used[j] && (best < 0 || Math.abs(cand.get(j) - tgt) < Math.abs(cand.get(best) - tgt))) best = j;
            if (best < 0) best = 0;
            used[best] = true; v[k] = cand.get(best);
        }
        int[] d = chd(c);
        for (int q = 1; q < Math.min(4, d.length); q++) {
            int pc = Math.floorMod(d[0] + d[q], 12);
            boolean has = false;
            for (int x : v) if (Math.floorMod(x, 12) == pc) has = true;
            if (has) continue;
            // doppelt vorhandene Tonklasse durch den fehlenden Akkordton ersetzen
            for (int k = 0; k < nv && !has; k++)
                for (int k2 = 0; k2 < nv; k2++)
                    if (k2 != k && Math.floorMod(v[k], 12) == Math.floorMod(v[k2], 12)) {
                        int best = v[k];
                        for (int p : cand) if (Math.floorMod(p, 12) == pc && (best == v[k] || Math.abs(p - v[k]) < Math.abs(best - v[k]))) best = p;
                        v[k] = best; has = true; break;
                    }
        }
        Arrays.sort(v);
        return v;
    }

    /** E-Moll natürlich (E Fis G A H C D), Tonleiterschritte. */
    static final int[] MIN = {0, 2, 3, 5, 7, 8, 10};
    static int step(int m, int steps) {
        int rel = Math.floorMod(m - 4, 12), o = m - rel, idx = 0;
        for (int k = 0; k < 7; k++) if (MIN[k] <= rel) idx = k;
        int k = idx + steps;
        return o + MIN[Math.floorMod(k, 7)] + 12 * Math.floorDiv(k, 7);
    }

    /** Leitmotiv „Oruuns Ruf“: {MIDI, Start (Schläge), Dauer (Schläge)}, 32 Schläge, drei Phrasen. */
    static final double[][] MOTIF = {
            {52, 0, 3}, {59, 3, 4}, {57, 7, 1.5}, {55, 8.5, 2.5},
            {55, 12, 2}, {62, 14, 3}, {60, 17, 1}, {59, 18, 4},
            {57, 24, 1.5}, {55, 25.5, 1}, {54, 26.5, 1.5}, {52, 28, 2.5}, {47, 30.5, 1.5}};
    static final int[][] PHRASES = {{0, 4}, {4, 8}, {8, 13}};
    /** dasselbe in E-Dur (Sieg) */
    static final double[][] MOTIF_MAJ = {
            {52, 0, 3}, {59, 3, 4}, {57, 7, 1.5}, {56, 8.5, 2.5},
            {56, 12, 2}, {64, 14, 3}, {61, 17, 1}, {59, 18, 4},
            {57, 24, 1.5}, {56, 25.5, 1}, {54, 26.5, 1.5}, {52, 28, 5}};
    static final int[][] PHRASES_MAJ = {{0, 4}, {4, 8}, {8, 12}};

    // ================================================================================== Spur (Ringpuffer)

    interface NoteFn { void play(double beat, double durB, int m, double vel); }

    static final class Track {
        final String name, out; final int n; final boolean loop; final double bpm; final Random r;
        final float[] L, R, SL, SRr;
        double rt60 = 6, size = 2.2, pre = 30, wet = 0.4, damp = 0.3, revLp = 4500, target = -16, fadeIn = 0, fadeOut = 0;
        int q = 6;
        String[] H = new String[0];

        Track(String name, String out, double seconds, boolean loop, double bpm, long seed) {
            this.name = name; this.out = out; this.n = ns(seconds); this.loop = loop; this.bpm = bpm; this.r = new Random(seed);
            L = new float[n]; R = new float[n]; SL = new float[n]; SRr = new float[n];
        }
        double sec(double beat) { return beat * 60.0 / bpm; }
        double seconds() { return (double) n / SR; }
        String chordAtBeat(double beat) { int b = Math.floorMod((int) Math.floor(beat / 4), H.length); return H[b]; }

        void put(double t0, float[] l, float[] rr, double gain, double pan, double send) {
            double a = (clamp(pan, -1, 1) + 1) * Math.PI / 4;
            double gl = Math.cos(a) * Math.sqrt(2) * gain, gr = Math.sin(a) * Math.sqrt(2) * gain;
            long i0 = Math.round(t0 * SR);
            for (int k = 0; k < l.length; k++) {
                long ii = i0 + k; int j;
                if (loop) j = (int) Math.floorMod(ii, (long) n);
                else { if (ii < 0) continue; if (ii >= n) break; j = (int) ii; }
                float vl = (float) (l[k] * gl), vr = (float) (rr[k] * gr);
                L[j] += vl; R[j] += vr; SL[j] += (float) (vl * send); SRr[j] += (float) (vr * send);
            }
        }
        void put(double t0, float[] m, double gain, double pan, double send) { put(t0, m, m, gain, pan, send); }
        void bars(String s) { H = s.trim().split("\\s+"); }
        /** Loop-Grundton: Frequenz auf ganze Perioden pro Loop gerundet, damit die Naht phasengleich ist */
        void drone(double f, double gain, double pan, double send) {
            double len = seconds(), fq = Math.max(1, Math.round(f * len)) / len, tr = Math.max(1, Math.round(0.11 * len)) / len;
            float[] o = new float[n];
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR, ph = TAU * fq * t;
                o[i] = (float) ((Math.sin(ph) + 0.28 * Math.sin(2 * ph + 0.3) + 0.08 * Math.sin(3 * ph)) * (1 + 0.2 * Math.sin(TAU * tr * t)));
            }
            put(0, o, gain, pan, send);
        }

        // -------- Bausteine
        NoteFn horn(double gain, double pan, double send) {
            return (b, d, m, v) -> { float[][] s = brass(r, mtof(m), sec(d), v, Math.min(0.35, sec(d) * 0.3), 0.6); put(sec(b), s[0], s[1], gain, pan, send); };
        }
        NoteFn choirN(int vow, double gain, double pan, double send) {
            return (b, d, m, v) -> { float[][] s = choir(r, mtof(m), sec(d), v, vow, Math.min(0.45, sec(d) * 0.35), 0.9); put(sec(b), s[0], s[1], gain, pan, send); };
        }
        NoteFn strLegato(double gain, double pan, double send, double fc) {
            return (b, d, m, v) -> { float[][] s = pad(r, mtof(m), sec(d), v, fc, Math.min(0.4, sec(d) * 0.3), 0.7, 5); put(sec(b), s[0], s[1], gain, pan, send); };
        }

        /** Flächen über die Akkordfolge (gleiche Akkorde hintereinander werden zu einem gehaltenen zusammengefasst). */
        void chords(int bar0, int bar1, int lo, int hi, int nv, double gain, double send, String kind, double p1, double att, double rel) {
            int[] prev = null;
            int b = bar0;
            while (b < bar1) {
                int e = b + 1;
                while (e < bar1 && H[e].equals(H[b])) e++;
                int[] v = voicing(H[b], lo, hi, prev, nv);
                prev = v;
                double t0 = sec(b * 4), d = sec((e - b) * 4);
                for (int k = 0; k < v.length; k++) {
                    double pan = nv == 1 ? 0 : -0.55 + 1.1 * k / (nv - 1);
                    float[][] s;
                    switch (kind) {
                        case "pad": s = pad(r, mtof(v[k]), d, 1, p1, att, rel, 6); break;
                        case "choir": s = choir(r, mtof(v[k]), d, 1, (int) p1, att, rel); break;
                        case "brass": s = brass(r, mtof(v[k]), d, 0.7, att, rel); break;
                        default: throw new IllegalArgumentException(kind);
                    }
                    put(t0 - 0.05, s[0], s[1], gain, pan, send);
                }
                b = e;
            }
        }
        /** Grundton im Bass pro Akkord */
        void bass(int bar0, int bar1, int lo, double gain, double att, double rel) {
            int b = bar0;
            while (b < bar1) {
                int e = b + 1;
                while (e < bar1 && H[e].equals(H[b])) e++;
                put(sec(b * 4) - 0.05, subNote(r, mtof(rootIn(H[b], lo)), sec((e - b) * 4), 1, att, rel), gain, 0, 0.05);
                b = e;
            }
        }
        /** Sternenglitzern: zufällige Akkordtöne als leise Glocken */
        void glitter(double t0, double t1, double perSec, int lo, int hi, double gain) {
            int cnt = (int) Math.round((t1 - t0) * perSec);
            for (int i = 0; i < cnt; i++) {
                double t = t0 + r.nextDouble() * (t1 - t0);
                String c = H.length == 0 ? "Em9" : chordAtBeat(t * bpm / 60);
                List<Integer> cand = new ArrayList<>();
                for (int p = lo; p <= hi; p++) if (inCh(c, p)) cand.add(p);
                int m = cand.get(r.nextInt(cand.size()));
                put(t, bell(r, mtof(m), 0.4 + 0.6 * r.nextDouble(), 0.9 + r.nextDouble() * 1.2), gain, r.nextDouble() * 1.6 - 0.8, 0.85);
            }
        }
        /** Endlos-Rauschen für Loops: Segmente mit gleichleistungs-Überblendung */
        void windLoop(double gain, double lo, double hi, double q, double send) {
            double seg = 16, x = 4, total = seconds();
            int cnt = (int) Math.ceil(total / seg);
            double segLen = total / cnt;
            for (int s = 0; s < cnt; s++) {
                for (int side = 0; side < 2; side++) {
                    float[] w = wind(r, segLen + x, lo, hi, q);
                    for (int i = 0; i < w.length; i++) {
                        double t = (double) i / SR, g = 1;
                        if (t < x) g = Math.sin(Math.PI / 2 * t / x);
                        else if (t > segLen) g = Math.cos(Math.PI / 2 * Math.min(1, (t - segLen) / x));
                        w[i] *= (float) g;
                    }
                    float[] z = new float[w.length];
                    if (side == 0) put(s * segLen - x, w, z, gain, -1, send); else put(s * segLen - x, z, w, gain, 1, send);
                }
            }
        }
        /** Motiv-Noten über ein Instrument spielen */
        void motif(double[][] mot, int from, int to, double startBeat, int tr, double ts, double vel, NoteFn fn) {
            for (int i = from; i < to; i++) fn.play(startBeat + mot[i][1] * ts, mot[i][2] * ts, (int) mot[i][0] + tr, vel);
        }
        /** Motiv-Phrasen als Walgesang (gleitende Rufe) */
        void whaleMotif(double[][] mot, int[][] phrases, double startBeat, int tr, double ts, double gain, double pan, double send, double whistle, double lp) {
            for (int[] ph : phrases) {
                List<double[]> pp = new ArrayList<>(), ap = new ArrayList<>(), op = new ArrayList<>(), wp = new ArrayList<>();
                double ps = sec(startBeat + mot[ph[0]][1] * ts), lead = 0.35;
                double prevM = mot[ph[0]][0] + tr - 3;
                pp.add(new double[]{0, prevM});
                ap.add(new double[]{0, 0});
                double tEnd = 0;
                for (int i = ph[0]; i < ph[1]; i++) {
                    double s = sec(startBeat + mot[i][1] * ts) - ps + lead, d = sec(mot[i][2] * ts), m = mot[i][0] + tr;
                    double gl = Math.min(0.55, d * 0.4);
                    pp.add(new double[]{s, prevM});
                    pp.add(new double[]{s + gl, m});
                    pp.add(new double[]{s + d * 0.92, m + (i == ph[1] - 1 ? -0.4 : 0)});
                    ap.add(new double[]{s + Math.min(0.3, d * 0.3), i == ph[0] ? 1 : 0.95});
                    ap.add(new double[]{s + d * 0.85, 0.82});
                    op.add(new double[]{s + gl, clamp(0.25 + (m - tr - 47) / 18.0, 0.1, 0.95)});
                    wp.add(new double[]{s, 3 + r.nextInt(3)});
                    wp.add(new double[]{s + d, 4 + r.nextInt(4)});
                    prevM = m; tEnd = s + d;
                }
                pp.add(new double[]{tEnd + 0.9, prevM - 2.5});
                ap.add(new double[]{tEnd + 0.9, 0});
                Voice v = new Voice();
                v.pitch = new Curve(pp); v.amp = new Curve(ap); v.open = new Curve(op); v.whistleH = new Curve(wp);
                v.whistle = whistle; v.lpHz = lp; v.vib = 0.1; v.vibGrow = 0.8;
                put(ps - lead, whale(r, v), gain, pan, send);
            }
        }
        /** frei gleitender Walruf (pentatonische Stützpunkte) */
        void freeCall(double t0, int base, double len, double gain, double pan, double send, double lp, double whistle) {
            int[] pent = {-5, -3, 0, 3, 5, 7, 10, 12};
            int pts = 4 + r.nextInt(3);
            List<double[]> pp = new ArrayList<>(), ap = new ArrayList<>(), wp = new ArrayList<>(), op = new ArrayList<>();
            pp.add(new double[]{0, base - 4});
            ap.add(new double[]{0, 0});
            for (int k = 1; k <= pts; k++) {
                double t = len * k / (pts + 1);
                pp.add(new double[]{t, base + pent[r.nextInt(pent.length)]});
                ap.add(new double[]{t, 0.6 + 0.4 * r.nextDouble()});
                wp.add(new double[]{t, 3 + r.nextInt(6)});
                op.add(new double[]{t, r.nextDouble()});
            }
            pp.add(new double[]{len, base - 5});
            ap.add(new double[]{len, 0});
            Voice v = new Voice();
            v.pitch = new Curve(pp); v.amp = new Curve(ap); v.whistleH = new Curve(wp); v.open = new Curve(op);
            v.whistle = whistle; v.lpHz = lp; v.vib = 0.15;
            put(t0, whale(r, v), gain, pan, send);
        }
    }

    // ================================================================================== Musikstücke

    static Track phase1() {
        Track T = new Track("whale_phase1", "music/whale_phase1", 32 * 4, true, 60, 101);
        T.bars("Em Em C C G G D D Em Em Am Am C C Bsus B   C C G G Am Am Em Em C C D D Em Em Bsus B");
        T.rt60 = 7.5; T.size = 2.6; T.wet = 0.55; T.pre = 45; T.target = -16;
        T.chords(0, 32, 40, 64, 4, 0.20, 0.5, "pad", 850, 2.5, 3.5);         // tiefe warme Flächen
        T.chords(16, 32, 59, 74, 3, 0.055, 0.75, "pad", 1300, 3.5, 4);       // höhere Fläche im B-Teil
        T.bass(0, 32, 28, 0.13, 1.5, 2.5);                                    // Grundton E1-Lage
        for (int b = 0; b < 32; b++) {                                        // langsamer Puls (Herzschlag)
            T.put(T.sec(b * 4), thump(T.r, 70, 44, 1, 0.45, 1.5), 0.26, 0, 0.25);
            T.put(T.sec(b * 4 + 0.55), thump(T.r, 60, 40, 1, 0.35, 1.2), 0.12, 0, 0.25);
            if (b >= 16) T.put(T.sec(b * 4 + 2), thump(T.r, 66, 42, 1, 0.4, 1.4), 0.15, 0, 0.25);
        }
        // Leitmotiv als Walgesang (eine Oktave tiefer: E2), zweimal; dazwischen ferne Antworten
        T.whaleMotif(MOTIF, PHRASES, 32, -12, 1, 0.85, -0.1, 0.55, 0.55, 2800);
        T.whaleMotif(MOTIF, PHRASES, 96, -12, 1, 0.85, 0.1, 0.55, 0.65, 3000);
        T.motif(MOTIF, 0, 13, 96, 0, 1, 0.5, T.horn(0.22, 0.25, 0.6));          // leises Horn im 2. Durchgang
        T.freeCall(T.sec(6), 47, 7, 0.22, -0.6, 0.9, 1600, 0.8);              // Rufe aus der Ferne
        T.freeCall(T.sec(70), 50, 6.5, 0.2, 0.6, 0.9, 1600, 0.8);
        T.freeCall(T.sec(118), 45, 6, 0.16, -0.4, 0.95, 1400, 0.7);
        T.glitter(0, T.seconds(), 0.3, 76, 90, 0.05);
        T.windLoop(0.05, 180, 650, 1.6, 0.3);
        return T;
    }

    static Track phase2() {
        Track T = new Track("whale_phase2", "music/whale_phase2", 48 * 4 * 60 / 90.0, true, 90, 202);
        T.bars("Em Em C C Em Em C B   Em Em Am Am C C Bsus B   C C D D Em Em Bsus B   "
                + "Em Em F F Em Em C C Am Am F F B B B7 B7   Em Em C C Am Am B B");
        T.rt60 = 5; T.size = 2.2; T.wet = 0.42; T.pre = 30; T.target = -16;
        T.chords(0, 48, 40, 62, 4, 0.15, 0.5, "pad", 520, 1.5, 2.2);
        T.chords(24, 40, 47, 60, 3, 0.09, 0.6, "choir", 2, 1.2, 1.5);        // dunkler Chor „u“ im B-Teil
        T.bass(0, 48, 28, 0.13, 0.6, 1.0);
        // Ostinato tiefe Streicher (Achtel, 3+3+2)
        int[] pat = {0, 0, 2, 0, 1, 0, 2, 3};
        for (int b = 0; b < 48; b++) {
            String c = T.H[b];
            int r0 = rootIn(c, 38), th = r0, fi = r0;
            for (int x = r0 + 1; x <= r0 + 12; x++) { if (inCh(c, x) && th == r0) th = x; }
            for (int x = th + 1; x <= r0 + 12; x++) { if (inCh(c, x) && fi == r0) fi = x; }
            int[] tones = {r0, th, fi, r0 + 12};
            double base = b < 4 ? 0.55 : 0.85;
            for (int e = 0; e < 8; e++) {
                double vel = (e == 0 || e == 3 || e == 6 ? 1 : 0.62) * base;
                T.put(T.sec(b * 4 + e * 0.5), pluck(T.r, mtof(tones[pat[e]]), T.sec(0.42), vel, 0.6), 0.3, -0.25, 0.25);
                T.put(T.sec(b * 4 + e * 0.5), pluck(T.r, mtof(tones[pat[e]] + 12), T.sec(0.35), vel * 0.6, 0.4), 0.13, 0.35, 0.3);
            }
        }
        // Trommeln
        for (int b = 0; b < 48; b++) {
            T.put(T.sec(b * 4), taiko(T.r, 48, 1, 0.55), 0.36, 0, 0.25);
            if (b < 8 && b % 2 == 1) continue;
            if (b >= 8) {
                T.put(T.sec(b * 4 + 1.5), taiko(T.r, 78, 0.8, 0.3), 0.33, -0.2, 0.2);
                T.put(T.sec(b * 4 + 3), taiko(T.r, 78, 0.9, 0.3), 0.36, 0.2, 0.2);
                T.put(T.sec(b * 4 + 3.5), taiko(T.r, 96, 0.5, 0.22), 0.22, 0.3, 0.2);
            } else T.put(T.sec(b * 4 + 3), taiko(T.r, 70, 0.6, 0.35), 0.3, 0, 0.25);
            if (b >= 24 && b < 40) T.put(T.sec(b * 4 + 2), taiko(T.r, 60, 0.85, 0.4), 0.4, 0, 0.25);
        }
        // Pauken: auf Akkordwechseln, Wirbel vor den Teilen
        for (int b = 0; b < 48; b++) if (b == 0 || !T.H[b].equals(T.H[b - 1])) T.put(T.sec(b * 4), timpani(T.r, mtof(rootIn(T.H[b], 40)), 0.9), 0.38, 0.1, 0.3);
        for (int b : new int[]{7, 23, 39, 47}) for (int k = 0; k < 16; k++)
            T.put(T.sec(b * 4 + k * 0.25), timpani(T.r, mtof(rootIn(T.H[b], 35)), 0.25 + 0.75 * k / 15.0), 0.22, -0.1, 0.3);
        // Motiv: Wal (Takt 8), Hörner + Wal-Antworten (Takt 16), Kopfmotiv-Sequenz (B-Teil), verkürzt (Takt 40)
        T.whaleMotif(MOTIF, PHRASES, 32, -12, 1, 0.8, -0.1, 0.45, 0.6, 3000);
        T.motif(MOTIF, 0, 13, 64, 0, 1, 0.8, T.horn(0.36, 0.15, 0.45));
        T.motif(MOTIF, 0, 13, 64, -12, 1, 0.6, T.horn(0.16, -0.15, 0.4));
        T.freeCall(T.sec(64 + 10), 50, 4, 0.2, -0.5, 0.8, 2200, 0.7);
        T.freeCall(T.sec(64 + 22), 47, 4, 0.2, 0.5, 0.8, 2200, 0.7);
        int[] seq = {52, 53, 52, 48, 45, 53, 47, 47};
        for (int k = 0; k < 8; k++) {
            double bt = (24 + 2 * k) * 4;
            T.horn(0.32, 0.1, 0.45).play(bt, 3, seq[k], 0.8);
            T.horn(0.32, 0.1, 0.45).play(bt + 3, 4.5, seq[k] + 7, 0.9);
        }
        T.freeCall(T.sec(38 * 4), 52, 5, 0.42, 0, 0.6, 2600, 0.9);         // Schrei vor dem C-Teil
        T.whaleMotif(MOTIF, PHRASES, 160, -12, 0.5, 0.7, 0, 0.45, 0.6, 3000);
        T.motif(MOTIF, 0, 13, 160, 0, 0.5, 0.85, T.horn(0.3, 0.2, 0.45));
        T.whaleMotif(MOTIF, PHRASES, 176, -12, 0.5, 0.7, 0, 0.45, 0.6, 3000);
        T.motif(MOTIF, 0, 13, 176, 0, 0.5, 0.85, T.horn(0.3, -0.2, 0.45));
        T.put(T.sec(39 * 4), gong(T.r, 62, T.sec(4), 1, true), 0.35, 0, 0.6);
        T.put(T.sec(47 * 4), gong(T.r, 58, T.sec(4), 1, true), 0.3, 0, 0.6);
        T.glitter(0, T.seconds(), 0.12, 76, 88, 0.04);
        T.windLoop(0.035, 160, 520, 1.6, 0.3);
        return T;
    }

    static Track phase3() {
        Track T = new Track("whale_phase3", "music/whale_phase3", 64 * 4 * 60 / 120.0, true, 120, 303);
        T.bars("Em Em C C Em Em B B   Em Em Am Am C C Bsus B   C C D D Em Em B B   "
                + "Em Em F F G G F F Em Em C C Am Am B B   Em Em Am Am C C Bsus B C C D D Em Em B B   Em Em F F Em Em B B");
        T.rt60 = 4.2; T.size = 2.0; T.wet = 0.38; T.pre = 25; T.target = -16;
        T.chords(0, 64, 45, 64, 4, 0.18, 0.5, "choir", 0, 0.8, 1.2);          // Chor „a“ (Männer)
        T.chords(8, 24, 57, 72, 3, 0.07, 0.6, "choir", 1, 0.8, 1.2);          // obere Lage „o“
        T.chords(40, 56, 57, 74, 4, 0.08, 0.6, "choir", 0, 0.6, 1.2);
        T.chords(24, 40, 40, 58, 3, 0.08, 0.4, "brass", 0, 0.5, 0.8);         // Blechakkorde im Ereignishorizont
        T.chords(0, 64, 40, 60, 3, 0.07, 0.4, "pad", 700, 1, 1.5);
        T.bass(0, 64, 28, 0.12, 0.3, 0.6);
        // 16tel-Ostinato
        int[] pat = {0, 0, 3, 0, 1, 0, 3, 2, 0, 0, 3, 0, 2, 1, 3, 2};
        for (int b = 0; b < 64; b++) {
            String c = T.H[b];
            int r0 = rootIn(c, 38), th = r0, fi = r0;
            for (int x = r0 + 1; x <= r0 + 12; x++) { if (inCh(c, x) && th == r0) th = x; }
            for (int x = th + 1; x <= r0 + 12; x++) { if (inCh(c, x) && fi == r0) fi = x; }
            int[] tones = {r0, th, fi, r0 + 12};
            for (int e = 0; e < 16; e++) {
                double vel = (e % 4 == 0 ? 1 : (e % 2 == 0 ? 0.7 : 0.5)) * (b >= 56 && b < 60 ? 0.7 : 0.9);
                T.put(T.sec(b * 4 + e * 0.25), pluck(T.r, mtof(tones[pat[e]]), T.sec(0.22), vel, 0.7), 0.28, -0.3, 0.2);
                if (b >= 8) T.put(T.sec(b * 4 + e * 0.25), pluck(T.r, mtof(tones[pat[e]] + 12), T.sec(0.2), vel * 0.6, 0.5), 0.12, 0.35, 0.25);
            }
        }
        // Trommeln
        for (int b = 0; b < 64; b++) {
            boolean big = b >= 8 && !(b >= 56 && b < 60);
            T.put(T.sec(b * 4), taiko(T.r, 46, 1, 0.6), 0.36, 0, 0.25);
            for (int k = 1; k < 4; k++) T.put(T.sec(b * 4 + k), taiko(T.r, 74, k == 2 ? 0.95 : 0.7, 0.3), big ? 0.34 : 0.22, k % 2 == 0 ? 0.15 : -0.15, 0.2);
            if (big) {
                T.put(T.sec(b * 4 + 1.5), taiko(T.r, 98, 0.6, 0.2), 0.2, 0.3, 0.2);
                T.put(T.sec(b * 4 + 3.5), taiko(T.r, 98, 0.7, 0.2), 0.22, -0.3, 0.2);
            }
            if (b % 4 == 3) for (int k = 0; k < 8; k++)                                  // Tom-Wirbel
                T.put(T.sec(b * 4 + 2 + k * 0.25), taiko(T.r, 110 - k * 6, 0.4 + 0.07 * k, 0.18), 0.22, -0.4 + 0.1 * k, 0.25);
            if (b % 8 == 0) T.put(T.sec(b * 4), thump(T.r, 90, 30, 1, 0.9, 2.2), 0.32, 0, 0.35);  // großer Schlag
            if (b == 0 || !T.H[b].equals(T.H[b - 1])) T.put(T.sec(b * 4), timpani(T.r, mtof(rootIn(T.H[b], 40)), 1), 0.36, 0.1, 0.3);
        }
        // Motiv im vollen Satz
        T.motif(MOTIF, 0, 13, 32, 0, 1, 0.9, T.horn(0.36, -0.1, 0.4));
        T.motif(MOTIF, 0, 13, 32, 0, 1, 0.9, T.choirN(0, 0.3, 0.15, 0.5));
        T.whaleMotif(MOTIF, PHRASES, 32, -12, 1, 0.6, 0, 0.4, 0.6, 3000);
        T.motif(MOTIF, 0, 13, 64, 12, 1, 0.85, T.choirN(1, 0.16, 0.1, 0.55));
        T.motif(MOTIF, 0, 13, 64, 0, 1, 0.9, T.horn(0.32, -0.15, 0.4));
        for (int i = 0; i < 12; i++) {                                                  // Terz darunter
            double[] nt = MOTIF[i];
            T.horn(0.22, 0.3, 0.4).play(64 + nt[1], nt[2], step((int) nt[0], -2), 0.75);
        }
        // Ereignishorizont: augmentiertes Motiv im tiefen Blech, Walschreie darüber
        T.motif(MOTIF, 0, 8, 96, -12, 2, 0.95, T.horn(0.4, 0, 0.35));
        T.freeCall(T.sec(104), 52, 5, 0.3, -0.4, 0.6, 2600, 0.9);
        T.freeCall(T.sec(136), 54, 5, 0.3, 0.4, 0.6, 2600, 0.9);
        // Höhepunkt: zweimal tutti
        T.motif(MOTIF, 0, 13, 160, 0, 1, 1, T.horn(0.36, -0.1, 0.4));
        T.motif(MOTIF, 0, 13, 160, 12, 1, 0.9, T.choirN(0, 0.26, 0.15, 0.5));
        T.whaleMotif(MOTIF, PHRASES, 160, -12, 1, 0.6, 0, 0.4, 0.6, 3000);
        T.motif(MOTIF, 0, 13, 192, -12, 1, 1, T.horn(0.38, 0, 0.35));
        T.motif(MOTIF, 0, 13, 192, 0, 1, 0.95, T.choirN(0, 0.3, -0.1, 0.5));
        for (int i = 0; i < 12; i++) { double[] nt = MOTIF[i]; T.choirN(1, 0.13, 0.25, 0.5).play(192 + nt[1], nt[2], step((int) nt[0], -2), 0.8); }
        T.whaleMotif(MOTIF, PHRASES, 192, 0, 1, 0.5, 0.1, 0.5, 0.7, 3200);
        // Abbau: Chor-Orgelpunkt und Anschwellen in den Loop-Anfang
        T.put(T.sec(56 * 4), choir(T.r, mtof(40), T.sec(32), 1, 2, 2, 1)[0], 0.12, -0.2, 0.5);
        T.put(T.sec(60 * 4), gong(T.r, 55, T.sec(16), 1, true), 0.4, 0, 0.6);
        T.windLoop(0.03, 160, 520, 1.6, 0.3);
        return T;
    }

    static Track victory() {
        Track T = new Track("whale_victory", "music/whale_victory", 40, false, 64, 404);
        T.bars("E E A E C#m A B E E E E");
        T.rt60 = 7; T.size = 2.6; T.wet = 0.5; T.pre = 40; T.target = -17; T.fadeIn = 0.05; T.fadeOut = 6;
        T.chords(0, 11, 44, 66, 4, 0.18, 0.55, "pad", 1100, 2.5, 4);
        T.chords(0, 10, 52, 68, 3, 0.1, 0.65, "choir", 1, 2, 3);
        T.bass(0, 11, 28, 0.13, 1.5, 3);
        T.whaleMotif(MOTIF_MAJ, PHRASES_MAJ, 0, -12, 1, 0.8, 0, 0.55, 0.6, 3000);
        T.motif(MOTIF_MAJ, 0, 12, 0, 0, 1, 0.55, T.horn(0.2, 0.2, 0.55));
        T.put(0, timpani(T.r, mtof(40), 0.8), 0.35, 0, 0.4);
        T.put(0, thump(T.r, 80, 40, 1, 1.2, 1.6), 0.25, 0, 0.4);
        T.freeCall(T.sec(32), 59, 6, 0.28, 0, 0.8, 3400, 0.9);                         // letzter heller Ruf
        T.glitter(0.5, 36, 0.9, 76, 93, 0.06);                                          // das Sternbild entsteht
        for (int k = 0; k < 8; k++) T.put(T.sec(32) + k * 0.35, bell(T.r, mtof(new int[]{76, 80, 83, 88, 87, 83, 80, 76}[k]), 0.8, 2.5), 0.08, -0.6 + 0.17 * k, 0.8);
        return T;
    }

    static Track ambient() {
        Track T = new Track("ambient_deep", "ambient/deep", 64, true, 60, 505);
        T.bars("Em9");
        T.rt60 = 9; T.size = 2.8; T.wet = 0.6; T.pre = 60; T.target = -20; T.q = 4;
        T.windLoop(0.16, 140, 600, 1.4, 0.45);
        T.windLoop(0.05, 60, 160, 1.0, 0.2);
        T.drone(mtof(28), 0.08, 0, 0.1);                                           // tiefer Grundton
        T.drone(mtof(35), 0.04, 0, 0.1);
        for (int[] nt : new int[][]{{40, 0}, {47, 1}, {54, 0}, {55, 1}}) {
            float[][] p = pad(T.r, mtof(nt[0]), 30, 1, 380, 8, 8, 8);
            T.put(nt[1] * 16, p[0], p[1], 0.06, nt[1] == 0 ? -0.4 : 0.4, 0.6);
            p = pad(T.r, mtof(nt[0]), 30, 1, 380, 8, 8, 8);
            T.put(nt[1] * 16 + 32, p[0], p[1], 0.06, nt[1] == 0 ? 0.4 : -0.4, 0.6);
        }
        T.freeCall(5, 45, 7, 0.2, -0.5, 0.95, 1300, 0.8);
        T.freeCall(26, 50, 6, 0.17, 0.6, 0.95, 1200, 0.8);
        T.freeCall(46, 43, 8, 0.2, -0.1, 0.95, 1400, 0.8);
        T.put(14, gong(T.r, 48, 9, 1, true), 0.16, 0.3, 0.7);
        T.put(42, gong(T.r, 44, 10, 1, true), 0.16, -0.3, 0.7);
        T.glitter(0, 64, 0.14, 76, 91, 0.05);
        return T;
    }

    // ================================================================================== Hall

    /** Stereo-FDN-Hall: Vorverzögerung, Diffusions-Allpässe, 8 Leitungen (Householder), Dämpfung, leichte Modulation. */
    static float[][] reverb(float[] inL, float[] inR, int outLen, boolean loop, double rt60, double size, double preMs, double damp, double lpHz) {
        int n = loop ? inL.length : outLen;
        float[] oL = new float[n], oR = new float[n];
        Bq hL = Bq.hp(140, 0.7), hR = Bq.hp(140, 0.7), lL = Bq.lp(lpHz, 0.7), lR = Bq.lp(lpHz, 0.7);
        int pre = Math.max(1, (int) (preMs * SR / 1000));
        double[] pdL = new double[pre], pdR = new double[pre];
        int pw = 0;
        int[] apl = {142, 107, 379, 277}, apr = {151, 113, 389, 263};
        double[][] aL = new double[4][], aR = new double[4][];
        int[] pL = new int[4], pR = new int[4];
        for (int k = 0; k < 4; k++) { aL[k] = new double[(int) (apl[k] * Math.sqrt(size))]; aR[k] = new double[(int) (apr[k] * Math.sqrt(size))]; }
        int[] base = {1433, 1601, 1867, 2053, 2251, 2399, 2617, 2797};
        int N = 8, MOD = 12;
        double[][] dl = new double[N][];
        int[] dp = new int[N];
        double[] g = new double[N], lp = new double[N], o = new double[N], mr = new double[N], mp = new double[N];
        double loopSec = (double) n / SR;
        for (int j = 0; j < N; j++) {
            int len = (int) (base[j] * size);
            dl[j] = new double[len + MOD + 4];
            g[j] = Math.pow(10, -3.0 * len / (rt60 * SR));
            double rate = 0.07 + 0.05 * j;
            if (loop) rate = Math.max(1, Math.round(rate * loopSec)) / loopSec;   // periodisch über den Loop
            mr[j] = rate; mp[j] = j * 0.7;
        }
        int[] lens = new int[N];
        for (int j = 0; j < N; j++) lens[j] = (int) (base[j] * size);
        double[] sL = {1, -1, 1, -1, 1, -1, 1, -1}, sRr = {1, 1, -1, -1, 1, 1, -1, -1}, inS = {1, 1, -1, 1, -1, -1, 1, -1};
        int total = loop ? 2 * n : n;
        for (int ii = 0; ii < total; ii++) {
            int i = ii % n;
            double xl = i < inL.length ? inL[i] : 0, xr = i < inR.length ? inR[i] : 0;
            xl = lL.p(hL.p(xl)); xr = lR.p(hR.p(xr));
            double dL = pdL[pw], dR = pdR[pw];
            pdL[pw] = xl; pdR[pw] = xr; if (++pw == pre) pw = 0;
            for (int k = 0; k < 4; k++) {
                double[] b = aL[k]; int p = pL[k]; double y = -0.6 * dL + b[p]; b[p] = dL + 0.6 * y; pL[k] = (p + 1) % b.length; dL = y;
                b = aR[k]; p = pR[k]; y = -0.6 * dR + b[p]; b[p] = dR + 0.6 * y; pR[k] = (p + 1) % b.length; dR = y;
            }
            double sum = 0;
            for (int j = 0; j < N; j++) {
                double[] d = dl[j];
                double off = lens[j] + MOD * 0.5 * (1 + Math.sin(TAU * mr[j] * ii / SR + mp[j]));
                double rp = dp[j] - off; while (rp < 0) rp += d.length;
                int r0 = (int) rp; double fr = rp - r0; int r1 = r0 + 1; if (r1 >= d.length) r1 = 0;
                double v = d[r0] + (d[r1] - d[r0]) * fr;
                lp[j] = v * (1 - damp) + lp[j] * damp;
                o[j] = lp[j] * g[j]; sum += o[j];
            }
            double hh = sum * (2.0 / N), lateL = 0, lateR = 0;
            for (int j = 0; j < N; j++) {
                dl[j][dp[j]] = o[j] - hh + ((j & 1) == 0 ? dL : dR) * inS[j] * 0.35 + 1e-24;
                if (++dp[j] == dl[j].length) dp[j] = 0;
                lateL += o[j] * sL[j]; lateR += o[j] * sRr[j];
            }
            if (!loop || ii >= n) { oL[i] = (float) lateL; oR[i] = (float) lateR; }
        }
        return new float[][]{oL, oR};
    }

    // ================================================================================== Mastering

    static void eq(float[] x, boolean loop, Bq... chain) {
        int n = x.length;
        if (loop) for (int i = 0; i < n; i++) { double v = x[i]; for (Bq b : chain) v = b.p(v); }
        for (int i = 0; i < n; i++) { double v = x[i]; for (Bq b : chain) v = b.p(v); x[i] = (float) v; }
    }
    static Bq[] busEq() { return new Bq[]{Bq.hp(26, 0.707), Bq.peak(250, 0.8, -1.0), Bq.shelf(6500, -3.0, true), Bq.lp(14000, 0.707)}; }

    static double[] compress(float[] L, float[] R, boolean loop, double thr, double ratio, double attMs, double relMs) {
        int n = L.length;
        double ca = Math.exp(-1 / (attMs * 0.001 * SR)), cr = Math.exp(-1 / (relMs * 0.001 * SR)), cd = Math.exp(-1 / (0.005 * SR));
        double det = 1e-9, gdb = 0, maxRed = 0;
        float[] gain = new float[n];
        int passes = loop ? 2 : 1;
        for (int p = 0; p < passes; p++)
            for (int i = 0; i < n; i++) {
                double s = 0.5 * (L[i] * L[i] + R[i] * R[i]);
                det = cd * det + (1 - cd) * s;
                double over = 10 * Math.log10(det + 1e-12) - thr, knee = 8, red;
                if (over <= -knee / 2) red = 0;
                else if (over >= knee / 2) red = over * (1 - 1 / ratio);
                else { double x = over + knee / 2; red = (1 - 1 / ratio) * x * x / (2 * knee); }
                double tgt = -red;
                gdb = tgt < gdb ? ca * gdb + (1 - ca) * tgt : cr * gdb + (1 - cr) * tgt;
                if (p == passes - 1) { gain[i] = (float) gdb; maxRed = Math.max(maxRed, -gdb); }
            }
        for (int i = 0; i < n; i++) { float g = (float) Math.pow(10, gain[i] / 20); L[i] *= g; R[i] *= g; }
        return new double[]{maxRed};
    }

    static double at(float[] x, int i, boolean loop) { int n = x.length; if (loop) return x[Math.floorMod(i, n)]; return i < 0 || i >= n ? 0 : x[i]; }
    static double mid(float[] x, int i, boolean loop) { return (-at(x, i - 1, loop) + 9 * at(x, i, loop) + 9 * at(x, i + 1, loop) - at(x, i + 2, loop)) / 16.0; }

    /** Look-ahead-Limiter (Ring-fähig): die Verstärkung unterschreitet nie die benötigte, auch zwischen den Samples. */
    static void limit(float[] L, float[] R, boolean loop, double ceilDb) {
        int n = L.length, la = (int) (0.003 * SR);
        double ceil = db2g(ceilDb);
        double[] req = new double[n];
        for (int i = 0; i < n; i++) {
            double pk = Math.max(Math.abs(L[i]), Math.abs(R[i]));
            pk = Math.max(pk, Math.max(Math.abs(mid(L, i, loop)), Math.abs(mid(R, i, loop))));
            req[i] = pk > ceil ? ceil / pk : 1;
        }
        // gleitendes Minimum über ±la (monotone Warteschlange)
        double[] mn = new double[n];
        ArrayDeque<Integer> dq = new ArrayDeque<>();
        for (int e = -la; e < n + la; e++) {
            double v = loop ? req[Math.floorMod(e, n)] : (e < 0 || e >= n ? 1 : req[e]);
            while (!dq.isEmpty()) {
                int q = dq.peekLast();
                double qv = loop ? req[Math.floorMod(q, n)] : (q < 0 || q >= n ? 1 : req[q]);
                if (qv >= v) dq.pollLast(); else break;
            }
            dq.addLast(e);
            int c = e - la;
            if (c >= 0) {
                while (dq.peekFirst() < c - la) dq.pollFirst();
                int q = dq.peekFirst();
                mn[c] = loop ? req[Math.floorMod(q, n)] : (q < 0 || q >= n ? 1 : req[q]);
            }
        }
        double[] pre = new double[n + 2 * la + 2];
        for (int e = -la; e < n + la; e++) {
            double v = loop ? mn[Math.floorMod(e, n)] : (e < 0 || e >= n ? 1 : mn[e]);
            pre[e + la + 1] = pre[e + la] + v;
        }
        double c = 1 - Math.exp(-1 / (0.08 * SR)), g = 1;
        int passes = loop ? 2 : 1;
        for (int p = 0; p < passes; p++)
            for (int i = 0; i < n; i++) {
                double t = Math.min((pre[i + 2 * la + 1] - pre[i]) / (2 * la + 1), req[i]);
                if (t < g) g = t; else g += c * (t - g);
                if (p == passes - 1) { L[i] *= (float) g; R[i] *= (float) g; }
            }
    }

    /** BS.1770 integrierte Lautheit (K-Filter, 400-ms-Blöcke, 75 % Überlappung, absolutes + relatives Gate). R = null: mono */
    static double lufs(float[] L, float[] R) {
        Bq[] kl = kw(), kr = kw();
        int n = L.length, hop = (int) (0.1 * SR), segs = n / hop;
        if (segs < 4) { // sehr kurz: auf 400 ms auffüllen
            L = Arrays.copyOf(L, 4 * hop); if (R != null) R = Arrays.copyOf(R, 4 * hop); n = L.length; segs = 4;
        }
        double[] seg = new double[segs];
        for (int i = 0; i < segs * hop; i++) {
            double yl = kl[1].p(kl[0].p(L[i])), e = yl * yl;
            if (R != null) { double yr = kr[1].p(kr[0].p(R[i])); e += yr * yr; }
            seg[i / hop] += e;
        }
        List<Double> z = new ArrayList<>();
        for (int b = 0; b + 4 <= segs; b++) z.add((seg[b] + seg[b + 1] + seg[b + 2] + seg[b + 3]) / (4.0 * hop));
        double s = 0; int c = 0;
        for (double v : z) if (-0.691 + 10 * Math.log10(v + 1e-20) > -70) { s += v; c++; }
        if (c == 0) return -100;
        double thr = -0.691 + 10 * Math.log10(s / c) - 10;
        s = 0; c = 0;
        for (double v : z) if (-0.691 + 10 * Math.log10(v + 1e-20) > Math.max(-70, thr)) { s += v; c++; }
        return -0.691 + 10 * Math.log10(s / c);
    }
    static Bq[] kw() {
        double fc = 1681.9744509555319, G = 3.99984385397, Q = 0.7071752369554193;
        double A = Math.pow(10, G / 40), w = TAU * fc / SR, cs = Math.cos(w), al = Math.sin(w) / (2 * Q), sa = 2 * Math.sqrt(A) * al;
        Bq s1 = new Bq(A * ((A + 1) + (A - 1) * cs + sa), -2 * A * ((A - 1) + (A + 1) * cs), A * ((A + 1) + (A - 1) * cs - sa),
                (A + 1) - (A - 1) * cs + sa, 2 * ((A - 1) - (A + 1) * cs), (A + 1) - (A - 1) * cs - sa);
        return new Bq[]{s1, Bq.hp(38.13547087613982, 0.5003270373253953)};
    }
    static void scale(float[] x, double g) { for (int i = 0; i < x.length; i++) x[i] *= (float) g; }

    static final double CEIL = -1.6; // vor der Kodierung; Vorbis darf danach noch ~0,5 dB überschwingen

    static void masterTrack(Track T, StringBuilder log) {
        float[][] w = reverb(T.SL, T.SRr, T.n, T.loop, T.rt60, T.size, T.pre, T.damp, T.revLp);
        float[] L = T.L, R = T.R;
        for (int i = 0; i < T.n; i++) { L[i] += (float) (w[0][i] * T.wet); R[i] += (float) (w[1][i] * T.wet); }
        eq(L, T.loop, busEq()); eq(R, T.loop, busEq());
        if (!T.loop) {
            int fi = ns(T.fadeIn), fo = ns(T.fadeOut), n = T.n;
            for (int i = 0; i < fi; i++) { float g = (float) (0.5 - 0.5 * Math.cos(Math.PI * i / fi)); L[i] *= g; R[i] *= g; }
            for (int i = 0; i < fo; i++) { float g = (float) (0.5 - 0.5 * Math.cos(Math.PI * i / fo)); L[n - 1 - i] *= g; R[n - 1 - i] *= g; }
        }
        double g0 = db2g(-20 - lufs(L, R)); scale(L, g0); scale(R, g0);
        double[] cr = compress(L, R, T.loop, -21, 2.0, 20, 200);
        float[] L0 = L.clone(), R0 = R.clone();
        double gain = db2g(T.target - lufs(L0, R0)), m = 0;
        for (int it = 0; it < 6; it++) {
            for (int i = 0; i < T.n; i++) { L[i] = (float) (L0[i] * gain); R[i] = (float) (R0[i] * gain); }
            limit(L, R, T.loop, CEIL);
            m = lufs(L, R);
            if (Math.abs(m - T.target) < 0.1) break;
            gain *= db2g(T.target - m);
        }
        log.append(String.format("  %s: Kompressor max %.1f dB, Limiter-Ziel %.1f dBFS, LUFS (WAV) %.2f%n", T.name, cr[0], CEIL, m));
    }

    // ================================================================================== Effekte (mono)

    static final class Fx {
        final String name, out; final double target; float[] x;
        Fx(String name, String out, double target) { this.name = name; this.out = out; this.target = target; }
    }

    /** Mono-Hall für Effekte (trocken + Hallfahne, Länge wächst um tail Sekunden). */
    static float[] verb(float[] dry, double rt60, double size, double wet, double tail) {
        int n = dry.length + ns(tail);
        float[][] w = reverb(dry, dry, n, false, rt60, size, 20, 0.35, 4000);
        float[] o = Arrays.copyOf(dry, n);
        for (int i = 0; i < n; i++) o[i] += (float) ((w[0][i] + w[1][i]) * 0.5 * wet);
        return o;
    }

    static float[] trimTail(float[] x, double thrDb) {
        double thr = db2g(thrDb), p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        int end = x.length;
        while (end > 1 && Math.abs(x[end - 1]) < thr * p) end--;
        end = Math.min(x.length, end + ns(0.05));
        return Arrays.copyOf(x, end);
    }

    static void masterFx(Fx f, StringBuilder log) {
        float[] x = f.x;
        eq(x, false, Bq.hp(28, 0.707), Bq.shelf(6000, -3, true), Bq.lp(12000, 0.707));
        normPeak(x, 0.5);
        x = trimTail(x, -62);
        int fi = Math.min(ns(0.003), x.length / 4), fo = Math.min(ns(0.04), x.length / 3), n = x.length;
        for (int i = 0; i < fi; i++) x[i] *= (float) (i / (double) fi);
        for (int i = 0; i < fo; i++) x[n - 1 - i] *= (float) (0.5 - 0.5 * Math.cos(Math.PI * i / fo));
        float[] zero = new float[n];
        compress(x, x.clone(), false, -18, 2.0, 8, 150);
        // auf Ziel-Lautheit, aber höchstens 6 dB über die Spitzenbegrenzung hinaus
        double peak = 0; for (float v : x) peak = Math.max(peak, Math.abs(v));
        double gain = Math.min(db2g(f.target - lufs(x, null)), db2g(CEIL + 8) / peak);
        float[] x0 = x.clone();
        for (int i = 0; i < n; i++) x[i] = (float) (x0[i] * gain);
        float[] dup = x.clone();
        limit(x, dup, false, CEIL);
        f.x = x;
        log.append(String.format("  %s: LUFS (WAV) %.2f%n", f.name, lufs(x, null)));
    }

    static Voice call(Curve pitch, Curve amp, double growl, double sub, double whistle, Curve wh, Curve open) {
        Voice v = new Voice(); v.pitch = pitch; v.amp = amp; v.growl = growl; v.sub = sub; v.whistle = whistle; v.whistleH = wh; v.open = open;
        return v;
    }


    /** Druckstoß: bandpassgefiltertes Rauschen, dessen Mitte von fHi nach fLo fällt (das „Wummm“ einer Welle). */
    static float[] pressure(Random r, double dur, double fHi, double fLo, double tau) {
        int n = ns(dur);
        float[] o = new float[n];
        Noise nz = new Noise(r.nextLong());
        Svf b = new Svf(), l = new Svf().set(700, 0.7);
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            if ((i & 15) == 0) b.set(fLo + (fHi - fLo) * Math.exp(-t / (tau * 0.6)), 1.1);
            b.p(nz.next()); l.p(b.bpn());
            o[i] = (float) (Math.tanh(l.lp * 2.5 * hit(t, 0.008, tau)) * 0.8);
        }
        return o;
    }
    /** Rumpel-/Knirsch-Körner: kurze tiefpassgefilterte Rauschstöße */
    static float[] grains(Random r, double dur, int count, double fLo, double fHi, double tau, double decayAll, double startSpread) {
        float[] o = new float[ns(dur) + 1];
        Noise nz = new Noise(r.nextLong());
        for (int c = 0; c < count; c++) {
            double t0 = Math.pow(r.nextDouble(), 2.2) * startSpread;
            double f = fLo * Math.pow(fHi / fLo, r.nextDouble()), tt = tau * (0.4 + r.nextDouble() * 1.2);
            double a = Math.exp(-t0 / decayAll) * (0.4 + 0.6 * r.nextDouble());
            Svf s = new Svf().set(f, 1.2);
            int i0 = ns(t0), len = ns(tt * 5);
            for (int k = 0; k < len && i0 + k < o.length; k++) {
                s.p(nz.next());
                o[i0 + k] += (float) ((0.45 * s.lp + 0.9 * s.bpn()) * a * hit(k / (double) SR, 0.0005, tt));
            }
        }
        return o;
    }

    static List<Fx> effects() {
        List<Fx> fx = new ArrayList<>();
        // ---- Walgesang: drei Rufe
        fx.add(mk("song1", "whale/song1", -13, r -> verb(whale(r, call(
                new Curve(0, 41, 0.6, 48, 1.5, 52, 2.2, 47, 3.0, 55, 3.8, 54, 4.5, 44),
                new Curve(0, 0, 0.35, 1, 1.9, 0.75, 2.3, 0.9, 3.6, 0.85, 4.5, 0),
                0, 0.15, 0.7, new Curve(0, 3, 1.5, 6, 3, 4, 4.2, 7), new Curve(0, 0.2, 1.5, 0.7, 2.2, 0.3, 3.2, 0.9))), 4, 2.2, 0.35, 2.5)));
        fx.add(mk("song2", "whale/song2", -13, r -> verb(whale(r, call(
                new Curve(0, 38, 1.2, 50, 2.0, 57, 2.6, 55, 3.6, 49),
                new Curve(0, 0, 0.5, 0.9, 2.1, 1, 3.6, 0),
                0, 0.2, 0.6, new Curve(0, 2, 2, 5, 3.6, 3), new Curve(0, 0.1, 2, 0.95, 3.6, 0.4))), 4, 2.2, 0.35, 2.5)));
        fx.add(mk("song3", "whale/song3", -13, r -> verb(whale(r, call(
                new Curve(0, 36, 1.4, 43, 2.2, 41, 2.6, 41, 3.4, 53, 4.3, 58, 5.3, 50),
                new Curve(0, 0, 0.5, 1, 2.1, 0.8, 2.45, 0.15, 2.8, 0.9, 4.6, 0.8, 5.3, 0),
                0.1, 0.3, 0.8, new Curve(0, 4, 2, 3, 3, 5, 4.5, 8), new Curve(0, 0.15, 2, 0.4, 3.5, 0.85))), 4.5, 2.3, 0.38, 2.5)));
        // ---- Stöhnen: sehr tief, knarrend
        fx.add(mk("groan", "whale/groan", -12, r -> {
            Voice v = call(new Curve(0, 33, 0.8, 36, 2.2, 34, 3.2, 30), new Curve(0, 0, 0.4, 1, 2.4, 0.8, 3.2, 0), 0.55, 0.5, 0.3,
                    new Curve(0, 3, 3, 4), new Curve(0, 0.1, 1.5, 0.45, 3, 0.2));
            v.growlRate = 13; v.tilt = 1.3; v.lpHz = 2200;
            float[] o = whale(r, v);
            o = add(o, thump(r, 60, 30, 0.6, 1.2, 1.2), 0.5, 0);
            return verb(o, 3, 2, 0.3, 2);
        }));
        // ---- Getroffen
        fx.add(mk("hurt1", "whale/hurt1", -12, r -> {
            Voice v = call(new Curve(0, 48, 0.1, 57, 0.45, 55, 1.2, 43), new Curve(0, 0, 0.05, 1, 0.5, 0.8, 1.2, 0), 0.35, 0.3, 0.4,
                    new Curve(0, 5, 1.2, 3), new Curve(0, 0.9, 1.2, 0.3));
            v.vib = 0.05; v.growlRate = 22;
            float[] o = add(whale(r, v), thump(r, 110, 45, 1, 0.25, 2), 0.6, 0);
            return verb(o, 2.2, 1.6, 0.25, 1.4);
        }));
        fx.add(mk("hurt2", "whale/hurt2", -12, r -> {
            Voice v = call(new Curve(0, 45, 0.08, 53, 0.35, 52, 1.0, 40), new Curve(0, 0, 0.04, 1, 0.4, 0.75, 1.0, 0), 0.45, 0.4, 0.3,
                    new Curve(0, 4, 1, 2), new Curve(0, 0.8, 1, 0.2));
            v.vib = 0.05; v.growlRate = 19;
            float[] o = add(whale(r, v), thump(r, 100, 40, 1, 0.25, 2), 0.6, 0);
            return verb(o, 2.2, 1.6, 0.25, 1.4);
        }));
        // ---- Tod: langer letzter Gesang, sinkt in die Tiefe
        fx.add(mk("death", "whale/death", -12, r -> {
            Voice v = call(new Curve(0, 50, 0.8, 59, 1.8, 57, 2.6, 55, 3.4, 52, 4.2, 50, 5.0, 45, 6.3, 34),
                    new Curve(0, 0, 0.5, 1, 3.0, 0.9, 4.6, 0.7, 6.3, 0), 0.08, 0.25, 0.9,
                    new Curve(0, 4, 1.8, 8, 3.4, 6, 5, 10, 6.3, 5), new Curve(0, 0.3, 1.8, 0.95, 5, 0.4, 6.3, 0.1));
            v.vib = 0.12; v.vibGrow = 3; v.vibRate = 4.5;
            float[] o = whale(r, v);
            o = add(o, subNote(r, mtof(28), 3.5, 1, 1.5, 1.5), 0.35, 3.2);
            return verb(o, 6, 2.6, 0.42, 4);
        }));
        // ---- Auftauchen aus dem Sternenriss: Rauschen + Gesang + Sub
        fx.add(mk("breach", "whale/breach", -11, r -> {
            int n = ns(4.4);
            float[] o = new float[n];
            Noise nz = new Noise(r.nextLong());
            Svf a = new Svf(), b = new Svf();
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR, sw = smooth(t / 1.1) * Math.exp(-Math.max(0, t - 1.1) / 1.0);
                if ((i & 15) == 0) { a.set(180 + 1300 * smooth(t / 1.1) * Math.exp(-Math.max(0, t - 1.1) / 0.9), 0.8); b.set(900, 0.6); }
                a.p(nz.next()); b.p(a.lp);
                o[i] = (float) (b.lp * sw * 0.9);
            }
            o = add(o, subNote(r, mtof(26), 1.6, 1, 0.8, 1.6), 0.3, 0.2);
            o = add(o, thump(r, 70, 32, 1, 0.8, 1.8), 0.7, 1.0);
            Voice v = call(new Curve(0, 43, 0.7, 53, 1.6, 57, 2.6, 50, 3.1, 46), new Curve(0, 0, 0.4, 1, 2.4, 0.85, 3.1, 0), 0.1, 0.25, 0.7,
                    new Curve(0, 4, 3, 7), new Curve(0, 0.2, 1.6, 0.9));
            o = add(o, whale(r, v), 0.75, 0.8);
            return verb(o, 4, 2.4, 0.35, 2.5);
        }));
        // ---- Sternenriss öffnet sich: Reißen, aufsteigender schwebender Klang, Sub-Fall
        fx.add(mk("rift_open", "attack/rift_open", -12, r -> {
            float[] tear = grains(r, 2.6, 260, 250, 2200, 0.008, 1.6, 2.2);
            for (int i = 0; i < tear.length; i++) tear[i] *= (float) smooth(i / (double) ns(0.6));
            int n = ns(3.2);
            float[] tone = new float[n];
            double[] fr = {110, 155.6, 233.1, 329.6, 369.9};
            double[] ph = new double[fr.length];
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR, rise = Math.pow(2, 3 * smooth(t / 3.0) / 12), y = 0;
                for (int k = 0; k < fr.length; k++) { ph[k] += TAU * fr[k] * rise * (1 + 0.004 * Math.sin(TAU * (0.7 + k * 0.4) * t)) / SR; y += Math.sin(ph[k]) / (1 + k * 0.6); }
                tone[i] = (float) (y * env(t, 0.9, 2.2, 1.0) * 0.4);
            }
            float[] o = add(tear, tone, 1, 0);
            o = add(o, thump(r, 55, 28, 1, 0.9, 1.5), 0.6, 0);
            return verb(o, 3, 2.2, 0.35, 2);
        }));
        // ---- Gravitationswelle: tiefer Druckwellen-Boom mit Sub
        fx.add(mk("gravity_wave", "attack/gravity_wave", -10, r -> {
            int n = ns(3.2);
            float[] o = new float[n];
            Noise nz = new Noise(r.nextLong());
            Svf lp = new Svf();
            double ph = 0;
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR;
                double f = 28 + 85 * Math.exp(-t / 0.16);
                f *= 1 + 0.06 * Math.sin(TAU * 2.2 * t) * smooth((t - 0.4) / 0.5);
                ph += TAU * f / SR;
                if ((i & 15) == 0) lp.set(130 + 700 * Math.exp(-t / 0.12), 0.9);
                lp.p(nz.next());
                double y = 0.7 * Math.sin(ph) * hit(t, 0.004, 0.9) + 0.4 * Math.sin(2 * ph) * hit(t, 0.004, 0.3) + lp.lp * hit(t, 0.002, 0.4) * 1.8;
                o[i] = (float) (Math.tanh(y * 2.6) / 1.5);
            }
            o = add(o, pressure(r, 1.4, 420, 140, 0.4), 1.6, 0.01);
            return verb(o, 2.5, 2, 0.22, 1.5);
        }));
        // ---- Schwarzes Loch: dröhnendes, ziehendes Brummen
        fx.add(mk("black_hole", "attack/black_hole", -11, r -> {
            double dur = 4.2;
            int n = ns(dur);
            float[] o = new float[n];
            double[] fr = {41.2, 41.7, 61.7, 82.4, 83.1, 123.5};
            Saw[] s = new Saw[fr.length];
            for (int k = 0; k < fr.length; k++) s[k] = new Saw(r.nextDouble());
            Svf f1 = new Svf(), f2 = new Svf();
            Noise nz = new Noise(r.nextLong());
            Svf nb = new Svf();
            double wob = 0;
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR, u = t / dur;
                double pull = Math.pow(2, -2.5 * smooth(u) / 12);
                if ((i & 15) == 0) { f1.set(160 + 700 * smooth(u * 1.4) * (1 - 0.5 * smooth((u - 0.7) / 0.3)), 1.4); nb.set(200 + 300 * u, 1.5); }
                double y = 0;
                for (int k = 0; k < fr.length; k++) y += s[k].next(fr[k] * pull) / (1 + k * 0.3);
                f1.p(y / 3); f2.p(f1.lp);
                wob += TAU * (2.5 + 6 * u) / SR;
                nb.p(nz.next());
                double am = 0.75 + 0.25 * Math.sin(wob);
                o[i] = (float) ((Math.tanh(f1.lp * 1.6) * am + nb.bpn() * 0.25 * smooth(u * 2)) * env(t, 0.5, dur - 0.7, 0.7));
            }
            return verb(o, 2.5, 2, 0.25, 1.2);
        }));
        // ---- Trümmer: Felsrumpeln/Einschlag
        for (int k = 1; k <= 3; k++) {
            final int kk = k;
            fx.add(mk("debris" + k, "attack/debris" + k, -11, r -> {
                double dur = 1.2 + 0.25 * kk;
                float[] o = grains(r, dur, 110 + 30 * kk, 200, 2000 - 250 * kk, 0.012, 0.35, dur * 0.8);
                normPeak(o, 1);
                o = add(o, thump(r, 110 - 8 * kk, 62, 1, 0.2, 2.2), 0.3, 0);
                o = add(o, grains(r, dur, 40, 140, 600, 0.03, 0.6, dur * 0.7), 2.2, 0.02);
                return verb(o, 1.8, 1.5, 0.2, 1);
            }));
        }
        // ---- Sternenatem: kristallines Leuchten + Rauschen
        fx.add(mk("star_breath", "attack/star_breath", -12, r -> {
            double dur = 3.2;
            int n = ns(dur);
            float[] o = new float[n];
            double[] fr = {659.3, 987.8, 1174.7, 1480, 1318.5};
            double[][] ph = new double[fr.length][3];
            Noise nz = new Noise(r.nextLong());
            Svf nb = new Svf(), nl = new Svf().set(1400, 0.7);
            Saw hum = new Saw(0);
            Svf hl = new Svf().set(380, 0.8);
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR, e = env(t, 0.3, dur - 0.7, 0.7), y = 0;
                for (int k = 0; k < fr.length; k++)
                    for (int c = 0; c < 3; c++) {
                        ph[k][c] += TAU * fr[k] * (1 + (c - 1) * 0.004) * (1 + 0.002 * Math.sin(TAU * 5.5 * t + k)) / SR;
                        y += Math.sin(ph[k][c]) * (1 + 0.5 * Math.sin(TAU * (3 + k) * t + c)) / (3 * (1 + k * 0.5));
                    }
                if ((i & 15) == 0) nb.set(300 + 450 * (0.5 + 0.5 * Math.sin(TAU * 0.6 * t)), 0.9);
                nb.p(nz.next()); nl.p(nb.bpn());
                hl.p(hum.next(82.4));
                o[i] = (float) ((y * 0.22 + nl.lp * 0.55 + hl.lp * 0.35) * e);
            }
            return verb(o, 3, 2.2, 0.4, 1.8);
        }));
        // ---- Verschlingen: Einsaugen, gewaltiges Zuschnappen, Schlucken, Magenrumpeln
        fx.add(mk("swallow", "attack/swallow", -10, r -> {
            int n = ns(2.8);
            float[] o = new float[n];
            Noise nz = new Noise(r.nextLong());
            Svf a = new Svf();
            for (int i = 0; i < ns(0.4); i++) {
                double t = (double) i / SR;
                if ((i & 15) == 0) a.set(200 + 900 * smooth(t / 0.38), 0.8);
                a.p(nz.next());
                o[i] = (float) (a.lp * smooth(t / 0.35) * 0.6);
            }
            o = add(o, thump(r, 130, 55, 1, 0.3, 2.5), 1.0, 0.36);                      // Kiefer schlägt zu
            o = add(o, grains(r, 0.2, 30, 300, 900, 0.006, 0.05, 0.03), 1.4, 0.36);       // Knochen-Klacken
            for (double t0 : new double[]{0.8, 1.2}) {                                     // zweimal Schlucken
                int m = ns(0.3);
                float[] gl = new float[m];
                double ph = 0;
                Svf f = new Svf();
                for (int i = 0; i < m; i++) {
                    double t = (double) i / SR, fr = 70 + 160 * Math.exp(-t / 0.06);
                    ph += TAU * fr / SR;
                    if ((i & 7) == 0) f.set(fr * 3, 2.5);
                    f.p(Math.sin(ph) + 0.4 * Math.sin(2 * ph));
                    gl[i] = (float) (f.lp * hit(t, 0.01, 0.08));
                }
                o = add(o, gl, 0.6, t0);
            }
            float[] rum = new float[ns(1.4)];
            Svf rl = new Svf().set(90, 0.8);
            for (int i = 0; i < rum.length; i++) { rl.p(nz.next()); rum[i] = (float) (rl.lp * env(i / (double) SR, 0.3, 0.7, 0.7) * 1.2); }
            o = add(o, rum, 1, 1.3);
            Voice v = call(new Curve(0, 34, 0.5, 36, 1.0, 31), new Curve(0, 0, 0.2, 0.7, 1.0, 0), 0.5, 0.4, 0.2, new Curve(0, 3, 1, 3), new Curve(0, 0.2, 1, 0.1));
            v.growlRate = 12;
            o = add(o, whale(r, v), 0.35, 1.5);
            return verb(o, 2, 1.6, 0.2, 1.2);
        }));
        // ---- Supernova laden: 7 s ansteigend
        fx.add(mk("supernova_charge", "attack/supernova_charge", -12, r -> {
            double dur = 7.0;
            int n = ns(dur);
            float[] o = new float[n];
            int[] chord = {40, 47, 52, 55, 59};
            Saw[] s = new Saw[chord.length * 2];
            for (int k = 0; k < s.length; k++) s[k] = new Saw(r.nextDouble());
            Svf f = new Svf(), f2 = new Svf();
            Noise nz = new Noise(r.nextLong());
            Svf nb = new Svf(), nl = new Svf().set(1800, 0.7);
            double am = 0, sp = 0;
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR, u = t / dur;
                double rise = Math.pow(2, 19 * Math.pow(u, 1.6) / 12);
                if ((i & 15) == 0) { f.set(200 + 2200 * Math.pow(u, 1.5), 1.1); f2.set(400 + 2600 * u, 0.7); nb.set(200 + 1300 * u, 1.2); }
                double y = 0;
                for (int k = 0; k < chord.length; k++) {
                    double fr = mtof(chord[k]) * rise;
                    y += (s[2 * k].next(fr) + s[2 * k + 1].next(fr * 1.006)) / (2 * (1 + k * 0.3));
                }
                f.p(y * 0.4); f2.p(f.lp);
                am += TAU * (1.5 + 15 * u * u) / SR;
                double trem = 0.7 + 0.3 * Math.sin(am);
                nb.p(nz.next()); nl.p(nb.bpn());
                sp += TAU * 41.2 / SR;
                double sub = Math.sin(sp) * smooth(u) * 0.18;
                double env = smooth(u / 0.15) * (0.35 + 0.65 * u) * (t > dur - 0.25 ? 0.6 + 0.4 * (dur - t) / 0.25 : 1);
                o[i] = (float) ((Math.tanh(f2.lp * 2) * trem + nl.lp * 0.35 * u + sub) * env);
            }
            for (int i = n - ns(0.02); i < n; i++) o[i] *= (float) ((n - i) / (double) ns(0.02));
            return verb(o, 2.5, 2, 0.25, 0.6);
        }));
        // ---- Supernova-Explosion
        fx.add(mk("supernova_blast", "attack/supernova_blast", -9, r -> {
            int n = ns(5.0);
            float[] o = new float[n];
            Noise nz = new Noise(r.nextLong());
            Svf lp = new Svf();
            double ph = 0;
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR;
                double f = 32 + 80 * Math.exp(-t / 0.25);
                ph += TAU * f / SR;
                if ((i & 15) == 0) lp.set(180 + 2200 * Math.exp(-t / 0.35), 0.8);
                lp.p(nz.next());
                double y = Math.sin(ph) * hit(t, 0.004, 1.2) * 1.0 + lp.lp * hit(t, 0.002, 0.9) * 2.0;
                o[i] = (float) (Math.tanh(y * 2) / 1.6);
            }
            o = add(o, grains(r, 3.5, 160, 120, 900, 0.02, 1.2, 3.0), 0.9, 0.1);
            o = add(o, pressure(r, 2.5, 500, 120, 0.8), 0.8, 0.0);
            for (int m : new int[]{40, 47, 52, 55}) { float[][] b = brass(r, mtof(m), 2.2, 0.8, 0.25, 2.2); o = add(o, b[0], 0.18, 0.25); }
            return verb(o, 4, 2.4, 0.35, 2.5);
        }));
        // ---- Sternlinge: kleine glockige Zirp-/Gleitlaute
        for (int k = 1; k <= 3; k++) {
            final int kk = k;
            fx.add(mk("starling_ambient" + k, "starling/ambient" + k, -17, r -> {
                float[] o = new float[ns(1.2)];
                int chirps = 1 + kk % 3;
                for (int c = 0; c < chirps; c++) o = add(o, chirp(r, 76 + 2 * kk + r.nextInt(3), 81 + kk + r.nextInt(4), 0.22 + 0.1 * r.nextDouble()), 1, c * 0.2);
                return verb(o, 1.6, 1.2, 0.3, 0.9);
            }));
        }
        for (int k = 1; k <= 2; k++) {
            final int kk = k;
            fx.add(mk("starling_hurt" + k, "starling/hurt" + k, -15, r -> {
                float[] o = chirp(r, 86 - kk, 76 - kk, 0.2);
                o = add(o, chirp(r, 82 - kk, 72, 0.15), 0.6, 0.08);
                o = add(o, thump(r, 300, 140, 0.4, 0.04, 1.5), 0.5, 0);
                return verb(o, 1.4, 1.2, 0.25, 0.6);
            }));
        }
        fx.add(mk("starling_death", "starling/death", -15, r -> {
            float[] o = chirp(r, 84, 67, 0.7);
            for (int k = 0; k < 8; k++) o = add(o, bell(r, mtof(88 - k * 2), 0.5, 0.4), 0.25, 0.25 + k * 0.08);
            o = add(o, thump(r, 200, 90, 0.5, 0.06, 1.5), 0.4, 0);
            return verb(o, 1.8, 1.3, 0.3, 1);
        }));
        return fx;
    }

    /** Gleitender glockiger Zirplaut (Sinus + weiche Teiltöne) */
    static float[] chirp(Random r, double m0, double m1, double dur) {
        int n = ns(dur + 0.3);
        float[] o = new float[n];
        double ph = r.nextDouble() * TAU, p2 = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR, u = clamp(t / dur, 0, 1);
            double f = mtof(m0 + (m1 - m0) * smooth(u) + 0.25 * Math.sin(TAU * 11 * t));
            ph += TAU * f / SR; p2 += TAU * f * 2.76 / SR;
            double e = smooth(t / 0.02) * Math.exp(-Math.max(0, t - dur * 0.6) / 0.08);
            o[i] = (float) ((Math.sin(ph) + 0.18 * Math.sin(2 * ph) + 0.22 * Math.sin(p2) * Math.exp(-t / 0.08)) * e);
        }
        return o;
    }

    interface Gen { float[] g(Random r); }
    static final Map<String, Gen> FXGEN = new HashMap<>();
    static Fx mk(String name, String out, double target, Gen g) { Fx f = new Fx(name, out, target); FXGEN.put(name, g); return f; }

    // ================================================================================== Ausgabe / Prüfung

    static short[] quantise(float[] x, Random rn) {
        short[] q = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            double d = (rn.nextDouble() - rn.nextDouble()) / 32768.0;
            q[i] = (short) Math.round(clamp(x[i] + d, -1, 32767 / 32768.0) * 32768);
        }
        return q;
    }
    static void writeWav(Path p, short[]... ch) throws IOException {
        int c = ch.length, n = ch[0].length, data = n * c * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) c)
                .putInt(SR).putInt(SR * c * 2).putShort((short) (c * 2)).putShort((short) 16).put("data".getBytes()).putInt(data);
        for (int i = 0; i < n; i++) for (short[] s : ch) b.putShort(s[i]);
        Files.createDirectories(p.getParent());
        Files.write(p, b.array());
    }

    static String ffmpeg;

    static void encode(Path wav, Path ogg, int q) throws Exception {
        Files.createDirectories(ogg.getParent());
        Process p = new ProcessBuilder(ffmpeg, "-y", "-loglevel", "error", "-i", wav.toString(), "-c:a", "libvorbis", "-q:a", String.valueOf(q), "-map_metadata", "-1", ogg.toString())
                .redirectErrorStream(true).start();
        String outp = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException("ffmpeg: " + outp);
    }
    static float[][] decode(Path ogg, int ch) throws Exception {
        Process p = new ProcessBuilder(ffmpeg, "-loglevel", "error", "-i", ogg.toString(), "-f", "f32le", "-acodec", "pcm_f32le", "-").start();
        byte[] raw = p.getInputStream().readAllBytes();
        p.waitFor();
        FloatBuffer fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int n = fb.remaining() / ch;
        float[][] o = new float[ch][n];
        for (int i = 0; i < n; i++) for (int c = 0; c < ch; c++) o[c][i] = fb.get();
        return o;
    }


    /**
     * WAV schreiben, kodieren, zurückdekodieren; schwingt Vorbis über -1 dBFS hinaus, wird das Signal um den
     * Überschuss (+0,15 dB Reserve) leiser gemacht und neu kodiert.
     */
    static void encodeSafe(Path wav, Path ogg, int q, java.util.function.Supplier<short[][]> pcm, java.util.function.DoubleConsumer gain, int ch) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            writeWav(wav, pcm.get());
            encode(wav, ogg, q);
            double pk = 0;
            for (float[] c : decode(ogg, ch)) for (float v : c) pk = Math.max(pk, Math.abs(v));
            double over = g2db(pk) - (-1.05);
            if (over <= 0) return;
            gain.accept(db2g(-over - 0.15));
        }
    }
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -TAU / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
    /** Energieanteile in Bändern: <60, 60-250, 250-2k, 2k-6k, >6k Hz */
    static String bands(float[] x) {
        int N = 4096;
        double[] e = new double[5];
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(TAU * i / N);
        for (int s = 0; s + N <= x.length; s += N) {
            double[] re = new double[N], im = new double[N];
            for (int i = 0; i < N; i++) re[i] = x[s + i] * win[i];
            fft(re, im);
            for (int k = 1; k < N / 2; k++) {
                double f = (double) k * SR / N, p = re[k] * re[k] + im[k] * im[k];
                int b = f < 60 ? 0 : f < 250 ? 1 : f < 2000 ? 2 : f < 6000 ? 3 : 4;
                e[b] += p;
            }
        }
        double tot = 0; for (double v : e) tot += v;
        return String.format("Spektrum <60 %.0f%% | 60-250 %.0f%% | 250-2k %.0f%% | 2-6k %.1f%% | >6k %.2f%%",
                100 * e[0] / tot, 100 * e[1] / tot, 100 * e[2] / tot, 100 * e[3] / tot, 100 * e[4] / tot);
    }

    /** Prüft eine dekodierte OGG: Spitze, Lautheit, Länge, Loop-Naht. */
    static String check(String name, Path ogg, int ch, int expectN, boolean loop, double ceilMax) throws Exception {
        float[][] d = decode(ogg, ch);
        int n = d[0].length;
        double pk = 0;
        for (float[] c : d) for (float v : c) pk = Math.max(pk, Math.abs(v));
        double lu = lufs(d[0], ch > 1 ? d[1] : null);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-18s %6.2f s  %7.1f KB  Spitze %6.2f dBFS  %6.2f LUFS  Länge %s", name, n / (double) SR,
                Files.size(ogg) / 1024.0, g2db(pk), lu, n == expectN ? "ok" : ("ABWEICHUNG " + n + " statt " + expectN)));
        boolean bad = g2db(pk) > ceilMax || n != expectN;
        if (loop) {
            // Sprung an der Naht im Vergleich zu den Sprüngen im ganzen Stück (99,9 %-Quantil)
            double worst = 0;
            for (float[] c : d) {
                double[] dif = new double[n - 1];
                for (int i = 1; i < n; i++) dif[i - 1] = Math.abs(c[i] - c[i - 1]);
                double[] s = dif.clone(); Arrays.sort(s);
                double q = s[(int) (s.length * 0.999)];
                double seam = Math.abs(c[0] - c[n - 1]);
                worst = Math.max(worst, seam / Math.max(q, 1e-9));
            }
            sb.append(String.format("  Naht %.2f×Q99.9 %s", worst, worst < 1 ? "ok" : "PRÜFEN"));
            if (worst >= 1) bad = true;
        }
        sb.append("\n      ").append(bands(d[0]));
        if (bad) sb.append("\n      !!! FEHLER");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Aufruf: java tools/sounds/GenWhaleAudio.java <ffmpeg.exe> [name,name,...]"); System.exit(1); }
        ffmpeg = args[0];
        Set<String> only = args.length > 1 ? new HashSet<>(Arrays.asList(args[1].split(","))) : null;
        Path work = Paths.get("build/whale_audio"), assets = Paths.get("src/main/resources/assets/hha_void/sounds");
        Files.createDirectories(work);
        StringBuilder log = new StringBuilder();
        List<Callable<String>> jobs = new ArrayList<>();
        List<java.util.function.Supplier<Track>> tracks = List.of(GenWhaleAudio::phase1, GenWhaleAudio::phase2, GenWhaleAudio::phase3, GenWhaleAudio::victory, GenWhaleAudio::ambient);
        String[] tnames = {"whale_phase1", "whale_phase2", "whale_phase3", "whale_victory", "ambient_deep"};
        for (int i = 0; i < tracks.size(); i++) {
            if (only != null && !only.contains(tnames[i])) continue;
            final java.util.function.Supplier<Track> sup = tracks.get(i);
            jobs.add(() -> {
                long t0 = System.currentTimeMillis();
                Track T = sup.get();
                StringBuilder l = new StringBuilder();
                masterTrack(T, l);
                Path wav = work.resolve(T.name + ".wav"), ogg = assets.resolve(T.out + ".ogg");
                encodeSafe(wav, ogg, T.q, () -> { Random dq = new Random(7); return new short[][]{quantise(T.L, dq), quantise(T.R, dq)}; }, g -> { scale(T.L, g); scale(T.R, g); }, 2);
                l.append(check(T.name, ogg, 2, T.n, T.loop, -1.0)).append(String.format("   (%.0f s Rechenzeit)%n", (System.currentTimeMillis() - t0) / 1000.0));
                System.out.print(l);
                return l.toString();
            });
        }
        List<Fx> fx = effects();
        for (Fx f : fx) {
            if (only != null && !only.contains(f.name)) continue;
            jobs.add(() -> {
                f.x = FXGEN.get(f.name).g(new Random(f.name.hashCode() * 31L + 17));
                StringBuilder l = new StringBuilder();
                masterFx(f, l);
                Path wav = work.resolve(f.name + ".wav"), ogg = assets.resolve(f.out + ".ogg");
                encodeSafe(wav, ogg, 5, () -> new short[][]{quantise(f.x, new Random(3))}, g -> scale(f.x, g), 1);
                l.append(check(f.name, ogg, 1, f.x.length, false, -1.0)).append('\n');
                System.out.print(l);
                return l.toString();
            });
        }
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        for (Future<String> fu : ex.invokeAll(jobs)) log.append(fu.get());
        ex.shutdown();
        long total = 0;
        try (var st = Files.walk(assets)) { for (Path p : (Iterable<Path>) st::iterator) if (p.toString().endsWith(".ogg")) total += Files.size(p); }
        log.append(String.format("%nGesamtgröße aller OGGs: %.2f MB%n", total / 1048576.0));
        Files.writeString(work.resolve("report.txt"), log.toString());
        System.out.printf("%nGesamtgröße aller OGGs: %.2f MB%n", total / 1048576.0);
        if (log.toString().contains("!!! FEHLER")) { System.err.println("Prüfung fehlgeschlagen, siehe build/whale_audio/report.txt"); System.exit(2); }
    }
}
