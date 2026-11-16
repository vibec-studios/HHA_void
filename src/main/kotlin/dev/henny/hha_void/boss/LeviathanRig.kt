package dev.henny.hha_void.boss

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Gelenkwerte („Rig") des Leviathans je Haltung – gemeinsam für Server und Client.
 *
 * Wörtliche Kopie von `client/boss/VoidBossModel.rig()` (Optik: Fable), damit der Server die Hitboxen
 * ([LeviathanBody.Skeleton]) aus genau denselben Gelenkwinkeln rechnet, die das Modell zeigt. Ändert sich dort eine
 * Haltung, hier mitziehen (nur die Kanäle bis [JAW], Hinterleib [S1]/[Y1]/[G1], [TWIST], [TAILSCALE], Flossen und
 * [LOBE]/[FLUKE_ROLL] wirken auf die Boxen).
 */
object LeviathanRig {

    /** Rig der Haltung [pose] im Alter [a] (Ticks), [t] = Alter der Entity (für Dauerbewegungen), mit Überblendung. */
    fun shown(pose: Int, a: Float, previous: Int, previousAge: Float, t: Float, phase: Int): FloatArray {
        val r = rig(pose, a, t, phase)
        val fade = blendTicks(previous, pose)
        if (previous != WhalePose.HIDDEN && previous != pose && a < fade) {
            val from = rig(previous, previousAge, t, phase)
            val k = smooth(0f, fade, a)
            var roll = from[ROOT_ROLL]
            while (roll - r[ROOT_ROLL] > PI) roll -= TAU
            while (roll - r[ROOT_ROLL] < -PI) roll += TAU
            for (i in r.indices) {
                val f = if (i == ROOT_ROLL) roll else from[i]
                r[i] = f + (r[i] - f) * k
            }
        }
        return r
    }

    private fun base(phase: Int): FloatArray {
        val r = FloatArray(COUNT)
        r[SCALE] = 1f
        r[TAILSCALE] = 1f
        r[FL_SWEEP] = 0.62f; r[FR_SWEEP] = 0.62f
        r[FL_FLAP] = -0.28f; r[FR_FLAP] = -0.28f
        r[FL_ELBOW] = -0.16f; r[FR_ELBOW] = -0.16f
        r[WAVE] = 1f; r[FINWAVE] = 1f; r[GLIDE] = 1f
        r[THROAT] = 1f; r[TGLOW] = 0.7f; r[PLEAT] = 0.04f
        r[HEART] = if (phase >= 3) 1.25f else 1f
        r[FLARE] = if (phase >= 3) 0.3f else 0.12f
        r[HALO] = if (phase >= 3) 1.4f else 1f
        r[TREMBLE] = if (phase >= 3) 0.0015f else 0f
        // nach dem Ausbruch bleibt die Flanke offen
        r[WOUND] = if (phase >= 3) 1f else 0f
        r[RAYS] = if (phase >= 3) 0.6f else 0f
        for (i in 0 until 6) r[G1 + i] = 1f
        return r
    }

    private fun fins(r: FloatArray, sweep: Float, flap: Float, elbow: Float, k: Float) {
        r[FL_SWEEP] = lerp(r[FL_SWEEP], sweep, k); r[FR_SWEEP] = lerp(r[FR_SWEEP], sweep, k)
        r[FL_FLAP] = lerp(r[FL_FLAP], flap, k); r[FR_FLAP] = lerp(r[FR_FLAP], flap, k)
        r[FL_ELBOW] = lerp(r[FL_ELBOW], elbow, k); r[FR_ELBOW] = lerp(r[FR_ELBOW], elbow, k)
    }

    /** Biegt den Hinterleib: [v] = Neigung je Gelenk (Bauch, Hüfte, Schwanz 1-3, Fluke), gewichtet mit [k]. */
    private fun curl(r: FloatArray, k: Float, vararg v: Float) {
        for (i in v.indices) r[S1 + i] += v[i] * k
    }

    /**
     * Liegt schwer auf der linken Seite (gestrandet, taumelnd): [roll] = wie weit gekippt, [flat] = wie weit vom
     * eigenen Gewicht plattgedrückt. Die Oberkanten folgen dann `LeviathanBody.BEACHED_PROFILE`: Nase leicht hoch,
     * der Schwanz hängt durch und dreht sich zurück, bis die Fluke flach am Boden liegt (Rampe auf den Rücken),
     * das Herz liegt frei an der rechten Flanke.
     */
    private fun lie(r: FloatArray, roll: Float, flat: Float) {
        r[ROOT_ROLL] = lerp(r[ROOT_ROLL], LIE_ROLL, roll)
        r[SQUASH] = lerp(r[SQUASH], LIE_SQUASH, flat)
        r[ROOT_PITCH] = lerp(r[ROOT_PITCH], -0.12f, flat)
        r[TWIST] = -LIE_ROLL * roll
        // Durchhang zur Welt-Unterseite: die noch gerollten Gelenke brauchen dafür Neigung und Seitwärtsknick
        var turned = LIE_ROLL * roll
        for (i in 0 until 5) {
            turned += r[TWIST] * TWIST_SHARE[i]
            r[S1 + i] -= LIE_SAG[i] * flat * cos(turned)
            r[Y1 + i] += LIE_SAG[i] * flat * sin(turned)
        }
        r[S1 + 5] += 0.46f * flat
        r[HEAD] += 0.1f * flat
        // die untere (linke) Flosse liegt ausgestreckt am Boden, die obere stemmt sich
        r[FL_SWEEP] = lerp(r[FL_SWEEP], 0.3f, roll); r[FL_FLAP] = lerp(r[FL_FLAP], 1.05f, roll); r[FL_ELBOW] = lerp(r[FL_ELBOW], 0.04f, roll)
        r[FR_SWEEP] = lerp(r[FR_SWEEP], 0.35f, roll); r[FR_FLAP] = lerp(r[FR_FLAP], -1.1f, roll); r[FR_ELBOW] = lerp(r[FR_ELBOW], -0.2f, roll)
        r[WAVE] *= 1f - max(roll, flat)
        r[FINWAVE] *= 1f - max(roll, flat)
        r[GLIDE] = 1f
    }

    fun rig(pose: Int, a: Float, t: Float, phase: Int): FloatArray {
        val r = base(phase)
        when (pose) {
            WhalePose.SWIM -> {
                // ab und zu atmet er aus: die Lappen des Blaslochs flattern kurz auf
                val puff = max(0f, sin(t * 0.021f)).pow(40)
                r[BLOW] = 0.55f * puff
                r[PLEAT] += 0.04f * sin(t * 0.045f) + 0.1f * puff
            }
            WhalePose.EMERGE -> {
                // 0-40 schält er sich aus dem Riss: vorn zuerst, der Hinterleib wächst nach
                r[SCALE] = lerp(0.3f, 1f, smooth(0f, 42f, a))
                r[ROOT_Z] = lerp(64f, 0f, smooth(0f, 50f, a))
                r[G1] = smooth(3f, 24f, a); r[G1 + 1] = smooth(8f, 29f, a); r[G1 + 2] = smooth(13f, 35f, a)
                r[G1 + 3] = smooth(18f, 41f, a); r[G1 + 4] = smooth(24f, 47f, a); r[G1 + 5] = smooth(31f, 54f, a)
                // 40-90 bricht hervor und bäumt sich auf, 90-120 Maul weit auf
                val rear = env(a, 36f, 66f, 94f, 120f)
                val sing = env(a, 88f, 100f, 112f, 120f)
                val roar = env(a, 42f, 58f, 74f, 90f)
                r[ROOT_PITCH] = -0.36f * rear
                r[NECK] = -0.16f * rear - 0.06f * sing
                r[HEAD] = -0.2f * rear - 0.14f * sing
                curl(r, rear, -0.07f, -0.09f, -0.12f, -0.16f, -0.18f, -0.26f)
                r[JAW] = max(0.7f * roar, 1.15f * sing)
                r[THROAT] = 1f + 0.2f * sing * (1f + 0.5f * sin(a * 0.9f))
                r[PLEAT] = 0.8f * max(roar, sing)
                r[TGLOW] = 0.7f + 0.8f * max(roar, sing)
                r[TREMBLE] += 0.008f * roar
                fins(r, 1.2f, -0.1f, -0.05f, 1f - smooth(20f, 46f, a))
                fins(r, 0.1f, 0.3f, 0.14f, rear)
                r[WAVE] = 0.35f + 0.65f * (1f - rear)
                r[FLARE] = 0.12f + 0.25f * rear
                r[HALO] = 1f + 0.5f * max(roar, sing)
            }
            WhalePose.SONG -> {
                val k = env(a, 0f, 22f, 48f, 60f)
                val open = env(a, 4f, 30f, 50f, 60f)
                r[ROOT_PITCH] = -0.18f * k
                r[NECK] = -0.12f * k
                r[HEAD] = -0.18f * k
                r[JAW] = 0.85f * open + 0.04f * sin(a * 0.7f) * open
                r[THROAT] = 1f + open * (0.16f + 0.1f * sin(a * 0.9f))
                r[PLEAT] = open * (0.55f + 0.2f * sin(a * 0.9f))
                r[TGLOW] = 0.7f + 0.6f * open
                fins(r, 0.12f, 0.24f, 0.12f, k)
                curl(r, k, 0.02f, 0.03f, 0.04f, 0.05f, 0.05f, 0.1f)
                r[HEART] *= 1f + 0.3f * k
                r[FLARE] += 0.2f * k
                r[FINWAVE] = 1f - 0.5f * k
            }
            WhalePose.TAIL_SLAM -> {
                // dieselben Kurven wie die Hitboxen (LeviathanBody.segmentCenter): 0-24 heben, 24-28 peitschen
                val lift = smooth(0f, 24f, a) * (1f - hit(24f, 28f, a))
                val slam = hit(24f, 28f, a) * (1f - smooth(31f, 46f, a))
                // die Fluke hängt beim Heben nach und holt am Scheitel noch einmal aus
                val cock = smooth(11f, 24f, a) * (1f - hit(24f, 26.5f, a))
                val whip = hit(24f, 28f, a) * (1f - smooth(28f, 33f, a))
                val ring = osc(a, 28f, 0.5f, 7f)
                curl(r, lift, 0.21f, 0.23f, 0.23f, 0.21f, 0.1f, -0.2f)
                r[S1 + 5] += 0.65f * cock
                curl(r, slam, -0.07f, -0.09f, -0.19f, -0.19f, -0.07f, -0.32f)
                curl(r, ring, 0.02f, 0.04f, 0.07f, 0.1f, 0.12f, -0.3f)
                r[LOBE] = 0.3f * lift * (1f - 1.6f * cock) - 0.55f * whip + 0.3f * ring
                // der Vorderleib hält dagegen: taucht beim Ausholen ab, bäumt sich beim Schlag auf
                r[ROOT_PITCH] = 0.05f * lift - 0.07f * slam
                r[ROOT_Y] = 3f * lift - 5f * slam + 3f * ring
                r[NECK] = 0.05f * lift - 0.09f * slam
                r[HEAD] = 0.05f * lift - 0.1f * slam
                r[JAW] = 0.38f * slam
                fins(r, 0.25f, 0.35f, 0.15f, lift)
                fins(r, 0.95f, -0.42f, -0.22f, slam)
                r[TREMBLE] += 0.012f * pulse(a, 28f, 5f)
                r[WAVE] = 1f - max(lift, slam)
            }
            WhalePose.BREATH -> {
                val charge = smooth(0f, 25f, a)
                val fire = smooth(22f, 29f, a)
                // Phase III: zwei Durchgänge (hin und zurück), das Maul bleibt bis 140 offen
                val close = if (phase >= 3) 140f else 80f
                val k = 1f - smooth(close, close + 10f, a)
                val kick = osc(a, 25f, 0.45f, 9f) * k
                r[JAW] = lerp(0.45f * charge, 1.05f, fire) * k
                // lädt zurückgelehnt auf, stößt dann vor; der Rückstoß läuft durch den Körper
                r[ROOT_Z] = (7f * charge - 11f * fire + 4f * kick) * k
                r[HEAD] = (-0.14f * charge * (1f - fire) - 0.07f * fire) * k
                r[NECK] = -0.08f * charge * k
                r[HEAD_YAW] = sin((a - 25f) * 0.07f) * 0.05f * fire * k
                r[THROAT] = 1f + (0.35f * charge + 0.06f * sin(a * 1.3f) * fire) * k
                r[PLEAT] = (0.5f * charge + 0.5f * fire * (0.8f + 0.2f * sin(a * 0.9f))) * k
                r[TGLOW] = 0.7f + (0.6f * charge + 0.7f * fire) * k
                r[TREMBLE] += 0.007f * fire * k
                r[BLOAT] = (0.06f * charge * (1f - fire) - 0.015f * fire) * k
                fins(r, 0.25f, 0.1f, 0.05f, charge * k)
                curl(r, kick, 0.02f, 0.03f, 0.04f, 0.05f, 0.05f, 0.08f)
                r[WAVE] = 1f - 0.65f * charge * k
                r[HEART] *= 1f + 0.2f * charge * k
                r[FLARE] += 0.2f * fire * k
                r[HALO] += 0.4f * fire * k
            }
            WhalePose.DIVE -> {
                val k = smooth(0f, 8f, a)
                fins(r, 1.28f, -0.06f, -0.02f, k)
                r[NECK] = 0.03f * k
                r[HEAD] = 0.05f * k
                r[WAVE] = 1f + 0.7f * k
                r[FINWAVE] = 1f - 0.85f * k
                // im Sturz treibt er sich an, statt sich tragen zu lassen
                r[GLIDE] = 1f - 0.8f * k
            }
            WhalePose.BEACHED -> {
                val k = smooth(0f, 6f, a) * (1f - smooth(120f, 138f, a))
                val push = env(a, 116f, 126f, 131f, 140f)
                // gelegentliches Zucken von Kopf und Fluke, schwerer Atem
                val tw = max(0f, sin(a * 0.21f + 0.4f)).pow(6)
                val tw2 = max(0f, sin(a * 0.17f + 2.2f)).pow(6)
                val breath = sin(a * 0.16f)
                lie(r, k, k)
                // Aufprall: staucht tief ein und federt, der Schwanz klatscht nach
                r[SQUASH] += 0.13f * cos(a * 0.55f) * exp(-a / 6f) * k
                curl(r, osc(a, 2f, 0.5f, 8f) * k, 0f, 0.03f, 0.06f, 0.09f, 0.12f, 0.25f)
                r[TREMBLE] += 0.014f * pulse(a, 0f, 5f)
                r[HEAD] += (-0.12f * tw) * k
                r[HEAD_YAW] = 0.08f * tw * k
                r[JAW] = (0.24f + 0.22f * tw + 0.06f * breath) * k
                r[S1 + 4] += 0.1f * tw2 * k
                r[S1 + 5] += 0.45f * tw2 * k
                r[LOBE] = -0.2f * tw2 * k
                r[FR_FLAP] += (0.22f * sin(a * 0.25f)) * k
                r[FR_ELBOW] += (0.25f * sin(a * 0.25f - 0.9f)) * k
                // stemmt sich mit beiden Flossen hoch
                fins(r, 0.4f, -0.8f, -0.4f, push)
                r[THROAT] = 1f + 0.1f * breath * k
                r[PLEAT] = (0.22f + 0.16f * breath) * k
                r[BLOAT] = 0.02f * breath * k
                r[BLOW] = 0.5f * max(0f, breath).pow(3) * k
                r[HEART] *= 1f + k * (0.4f + 0.15f * sin(a * 0.5f))
                r[FLARE] = lerp(r[FLARE], 0.85f + 0.15f * sin(a * 0.5f), k)
            }
            WhalePose.INHALE -> {
                val k = env(a, 0f, 14f, 108f, 120f)
                val suck = 0.5f + 0.5f * sin(a * 0.42f)
                r[JAW] = 1.15f * k + 0.04f * sin(a * 0.9f) * k
                r[HEAD] = -0.22f * k
                r[NECK] = -0.1f * k
                r[ROOT_PITCH] = -0.05f * k
                r[HEAD_YAW] = 0.05f * sin(a * 0.06f) * k
                r[BLOAT] = k * (0.07f + 0.03f * suck)
                r[THROAT] = 1f + k * (0.5f + 0.1f * suck)
                r[PLEAT] = k * (0.8f + 0.2f * suck)
                r[TGLOW] = 0.7f + 1.0f * k
                // die Flossen stemmen sich als Bremse nach vorn, der Schwanz hält gegen
                fins(r, -0.2f, 0.15f + 0.12f * sin(a * 0.3f), 0.15f, k)
                curl(r, k, -0.02f, -0.03f, -0.04f, -0.04f, -0.04f, -0.1f)
                r[TREMBLE] += 0.005f * k
                r[HALO] += 0.3f * k
                r[WAVE] = 1f - 0.6f * k
                r[FINWAVE] = 1f - 0.7f * k
            }
            WhalePose.SWALLOW -> {
                val back = env(a, 0f, 5f, 7f, 12f)
                val open = smooth(2f, 15f, a)
                val snap = hit(17.5f, 20.5f, a)
                val lunge = smooth(7f, 18f, a) * (1f - smooth(24f, 44f, a))
                val rest = smooth(40f, 50f, a)
                val bite = pulse(a, 20f, 6f)
                r[JAW] = 1.3f * open * (1f - snap)
                // holt kurz aus, schnellt dann vor
                r[ROOT_Z] = 5f * back - 12f * lunge
                r[HEAD] = -0.34f * open * (1f - snap) + 0.13f * bite
                r[NECK] = -0.06f * back - 0.12f * open * (1f - snap)
                r[THROAT] = 1f + 0.25f * open * (1f - snap)
                r[PLEAT] = 0.6f * open * (1f - snap)
                r[TGLOW] = 0.7f + 0.9f * open * (1f - snap)
                // der Bissen wandert hinunter
                r[GULP_POS] = ((a - 21f) / 22f).coerceIn(0f, 1f)
                r[GULP_AMP] = 0.09f * snap * (1f - rest)
                r[TREMBLE] += 0.012f * bite
                fins(r, 1.05f, -0.1f, 0f, lunge)
                curl(r, bite, 0.02f, 0.03f, 0.04f, 0.05f, 0.05f, 0.1f)
                r[WAVE] = 1f + 0.5f * lunge
            }
            WhalePose.DEBRIS -> {
                val k = env(a, 0f, 26f, 50f, 70f)
                val c = a * 0.22f
                r[ROOT_ROLL] = 1.5f * k
                fins(r, 0f, 0.2f + 0.12f * sin(a * 0.2f), 0.15f, k)
                for (i in 1 until 6) {
                    r[Y1 + i] = (0.06f + 0.035f * i) * k * cos(c + 0.9f - i * 0.4f)
                    r[S1 + i] += (0.05f + 0.04f * i) * k * sin(c + 0.9f - i * 0.4f)
                }
                r[JAW] = 0.3f * k
                r[HEAD] = -0.08f * k
                r[PLEAT] = 0.3f * k
                r[HEART] *= 1f + 0.3f * k
                r[FLARE] += 0.25f * k
                r[WAVE] = 1f - 0.75f * k
                r[FINWAVE] = 1f - 0.6f * k
            }
            WhalePose.SUPERNOVA -> {
                val fold = smooth(0f, 40f, a)
                val burst = hit(106f, 110.5f, a)
                val c = fold * (1f - burst)
                val charge = smooth(0f, 108f, a)
                // C-Form: Kopf und Fluke zueinander
                r[NECK] = 0.3f * c - 0.12f * burst
                r[HEAD] = 0.32f * c - 0.22f * burst
                curl(r, c, -0.2f, -0.26f, -0.32f, -0.36f, -0.36f, -0.45f)
                curl(r, burst, 0.04f, 0.06f, 0.08f, 0.1f, 0.1f, 0.15f)
                fins(r, 1.1f, -1.0f, -0.6f, c)
                fins(r, -0.1f, 0.5f, 0.25f, burst)
                r[TREMBLE] += (0.004f + 0.03f * charge * charge) * (1f - burst) + 0.008f * burst
                r[HEART] *= 1f + 0.55f * charge
                r[FLARE] = lerp(0.3f, 1f, max(charge, burst))
                r[JAW] = 0.1f * c + 1.1f * burst
                r[THROAT] = 1f + 0.3f * burst
                r[PLEAT] = 0.25f * charge + 0.7f * burst
                r[TGLOW] = 0.7f + 0.8f * charge
                r[HALO] += 0.6f * charge
                r[BLOAT] = 0.05f * charge * (1f - burst) + 0.08f * burst
                r[WAVE] = 1f - fold
                r[FINWAVE] = 1f - fold
            }
            WhalePose.DEATH -> {
                // 0-50 letztes Brüllen, 50-90 bäumt sich senkrecht auf, 90-200 kippt zurück und rollt auf den Rücken
                val roar = env(a, 0f, 12f, 40f, 56f)
                val rear = smooth(50f, 88f, a)
                val scream = env(a, 52f, 66f, 86f, 104f)
                val fall = smooth(92f, 170f, a)
                val roll = smooth(92f, 200f, a)
                val limp = smooth(88f, 200f, a)
                val spasm = (pulse(a, 104f, 7f) + 0.7f * pulse(a, 134f, 7f) + 0.45f * pulse(a, 168f, 7f)).coerceAtMost(1f)
                r[ROOT_PITCH] = -0.2f * roar - 1.25f * rear * (1f - 0.92f * fall)
                r[ROOT_Y] = -6f * rear * (1f - fall)
                r[NECK] = -0.14f * roar - 0.16f * scream - 0.08f * limp
                r[HEAD] = -0.26f * roar - 0.2f * scream - 0.06f * limp + 0.08f * spasm
                r[HEAD_YAW] = 0.1f * sin(a * 0.3f) * scream
                r[JAW] = max(max(1.25f * roar, 1.0f * scream), 0.42f * limp) + 0.2f * spasm
                // rollt auf den Rücken und treibt; danach hängt alles zur Welt-Unterseite (= Modell-Oberseite)
                r[ROOT_ROLL] = PI.toFloat() * roll + 0.05f * limp * sin(t * 0.03f)
                fins(r, -0.05f, 0.45f, 0.25f, roar)
                fins(r, 0.2f, 0.3f + 0.35f * sin(a * 0.55f), 0.2f, scream)
                fins(r, 0.35f, 0.6f, 0.3f, limp)
                curl(r, roar, 0.03f, 0.05f, 0.07f, 0.08f, 0.09f, 0.22f)
                curl(r, scream, -0.07f, -0.09f, -0.12f, -0.15f, -0.16f, -0.28f)
                curl(r, limp, 0.04f, 0.06f, 0.09f, 0.12f, 0.14f, 0.3f)
                curl(r, spasm, -0.03f, -0.05f, -0.07f, -0.09f, -0.1f, -0.25f)
                r[LOBE] = -0.3f * limp
                r[THROAT] = 1f + 0.3f * roar
                r[PLEAT] = 0.8f * roar + 0.4f * scream
                r[HEART] *= 1f - 0.6f * smooth(100f, 220f, a)
                r[FLARE] = lerp(r[FLARE], 1f, rear) * (1f - smooth(150f, 220f, a))
                r[TGLOW] = 0.7f + 0.8f * roar - 0.5f * limp
                // das Licht in den Augen flackert und erlischt
                val flicker = if (a > 120f) 0.5f + 0.5f * sin(a * 1.7f) * sin(a * 0.37f) else 1f
                r[HALO] = (r[HALO] + 0.6f * roar) * (1f - smooth(120f, 215f, a)) * flicker
                r[RAYS] *= 1f - smooth(150f, 230f, a)
                r[TREMBLE] = 0.018f * roar + 0.01f * scream + 0.012f * spasm
                r[WAVE] = 1f - smooth(30f, 100f, a) * 0.95f
                r[FINWAVE] = 1f - smooth(30f, 100f, a) * 0.85f
            }
            WhalePose.STAGGER -> {
                val k = env(a, 0f, 3f, 8f, 20f)
                val ring = osc(a, 2f, 0.9f, 5f)
                r[ROOT_ROLL] = 0.22f * k
                r[ROOT_YAW] = 0.1f * k
                r[ROOT_X] = 4f * k
                r[HEAD_YAW] = -0.28f * k
                r[HEAD] = -0.1f * k
                r[JAW] = 0.5f * k
                r[FL_FLAP] += 0.5f * k * sin(a * 1.4f)
                r[FR_FLAP] -= 0.4f * k * sin(a * 1.4f + 1f)
                for (i in 1 until 6) r[Y1 + i] = 0.035f * i * k
                curl(r, ring, 0f, 0.03f, 0.05f, 0.08f, 0.1f, 0.2f)
                r[TREMBLE] += 0.01f * k
                r[HALO] += 0.5f * k
            }
            WhalePose.ROAR -> {
                // 0-4 duckt er sich (Ausholen), 4-12 reißt Kopf und Maul hoch, 12-48 brüllt und bebt, 48-60 schließen
                val coil = env(a, 0f, 3f, 4f, 9f)
                val k = env(a, 3f, 12f, 48f, 60f)
                val open = env(a, 4f, 12f, 47f, 57f)
                val quake = env(a, 11f, 15f, 40f, 52f)
                val thrust = env(a, 5f, 13f, 20f, 44f)
                r[ROOT_PITCH] = 0.05f * coil - 0.2f * k
                r[ROOT_Z] = 4f * coil - 7f * thrust
                r[NECK] = 0.08f * coil - 0.22f * k
                r[HEAD] = 0.1f * coil - 0.3f * k + 0.03f * sin(a * 1.9f) * quake + 0.07f * osc(a, 57f, 0.9f, 3f)
                r[HEAD_ROLL] = 0.04f * sin(a * 1.3f) * quake
                r[JAW] = 1.42f * open + 0.05f * sin(a * 2.3f) * quake
                r[THROAT] = 1f + open * (0.35f + 0.12f * sin(a * 1.6f))
                r[PLEAT] = open * (0.75f + 0.25f * sin(a * 1.6f))
                r[TGLOW] = 0.7f + 1.1f * open
                // holt tief Luft, die Brust fällt beim Brüllen zusammen
                r[BLOAT] = 0.06f * coil + 0.05f * open * (1f - smooth(14f, 46f, a))
                // Schallwelle bei Tick 14: ein Schlag geht durch den ganzen Körper
                val shock = pulse(a, 14f, 4f)
                r[TREMBLE] += 0.02f * quake + 0.03f * shock
                r[ROOT_Z] += 3f * shock
                // Flossen weit und hoch, Fluke steil
                fins(r, -0.1f, 0.5f, 0.3f, k)
                r[FL_FLAP] += 0.06f * sin(a * 1.9f) * quake
                r[FR_FLAP] += 0.06f * sin(a * 1.9f + 1.3f) * quake
                r[FL_FLAP] += 0.25f * shock; r[FR_FLAP] += 0.25f * shock
                curl(r, coil, -0.03f, -0.04f, -0.05f, -0.05f, -0.05f, -0.1f)
                curl(r, k, 0.05f, 0.07f, 0.1f, 0.12f, 0.14f, 0.36f)
                for (i in 0 until 6) r[S1 + i] += 0.012f * (i + 1) * sin(a * 1.9f - i * 0.8f) * quake
                r[LOBE] = -0.25f * k
                r[BLOW] = 0.6f * quake
                r[HEART] *= 1f + 0.35f * k
                r[FLARE] += 0.35f * k
                r[HALO] += 0.6f * open
                r[WAVE] = 1f - 0.75f * k
                r[FINWAVE] = 1f - 0.85f * k
            }
            WhalePose.BARREL_ROLL -> {
                // 0-20 Warnung: zittert, holt gegen die Drehrichtung aus und legt sich 15° hinein; 20-50 volle Rolle
                val wind = env(a, 0f, 8f, 12f, 19f)
                val lean = smooth(12f, 20f, a)
                val warn = env(a, 0f, 10f, 18f, 24f)
                val spin = env(a, 18f, 24f, 46f, 52f)
                val angle = when {
                    a < 20f -> -0.2f * wind + 0.26f * lean
                    a < 50f -> 0.26f + (TAU - 0.26f) * (a - 20f) / 30f
                    // pendelt aus (um die volle Drehung weitergezählt)
                    else -> 0.3f * sin((a - 50f) * 0.5f) * exp(-(a - 50f) / 6f)
                }
                r[ROOT_ROLL] = angle
                r[TREMBLE] += 0.012f * warn
                fins(r, 1.3f, -0.05f, -0.02f, env(a, 3f, 16f, 47f, 54f))
                // nach der Rolle fängt er sich mit ausgestellten Flossen
                fins(r, 0.1f, 0.35f, 0.2f, env(a, 49f, 53f, 55f, 60f))
                // Korkenzieher: der Schwanz eiert in der Drehung hinterher
                for (i in 0 until 6) {
                    r[Y1 + i] = 0.02f * (i + 1) * spin * sin(angle - 0.5f * i)
                    r[S1 + i] += 0.02f * (i + 1) * spin * cos(angle - 0.5f * i)
                }
                curl(r, wind, 0.02f, 0.03f, 0.04f, 0.04f, 0.04f, 0.08f)
                r[ROOT_X] = 2.5f * spin * sin(angle)
                r[ROOT_Y] = -2.5f * spin * (1f - cos(angle)) * 0.5f
                r[JAW] = 0.18f * warn + 0.1f * spin
                r[LOBE] = 0.2f * spin
                r[HALO] += 0.4f * warn
                r[WAVE] = 1f - 0.6f * max(warn, spin)
                r[FINWAVE] = 1f - 0.9f * max(warn, spin)
            }
            WhalePose.RAM -> {
                val k = smooth(0f, 8f, a)
                fins(r, 1.32f, -0.04f, -0.02f, k)
                // Stirn voran wie ein Rammbock
                r[NECK] = 0.07f * k
                r[HEAD] = 0.12f * k
                r[HEAD_ROLL] = 0.02f * sin(a * 0.8f) * k
                r[JAW] = 0.16f * k + 0.03f * sin(a * 0.9f) * k
                r[TGLOW] = 0.7f + 0.5f * k
                r[PLEAT] = 0.15f * k
                r[TREMBLE] += 0.003f * k
                r[FLARE] += 0.2f * k
                r[HALO] += 0.5f * k
                r[WAVE] = 1f + 0.8f * k
                r[FINWAVE] = 1f - 0.9f * k
                r[GLIDE] = 1f - k
            }
            WhalePose.BREACH -> {
                // 0-30 schießt senkrecht hoch (Durchstoß ~10), 30-50 bäumt sich am Scheitel, 50-70 kippt nach vorn
                val rise = env(a, 0f, 5f, 28f, 36f)
                val punch = pulse(a, 9f, 5f)
                val apex = env(a, 28f, 38f, 48f, 60f)
                val tip = smooth(50f, 70f, a)
                r[ROOT_PITCH] = -0.12f * apex + 0.5f * tip
                r[NECK] = -0.2f * apex + 0.1f * tip
                r[HEAD] = 0.06f * rise - 0.3f * apex + 0.12f * tip
                r[JAW] = 0.3f * punch + 1.15f * apex * (1f - tip)
                r[PLEAT] = 0.7f * apex
                r[THROAT] = 1f + 0.25f * apex
                r[TGLOW] = 0.7f + 0.9f * apex
                curl(r, apex, 0.08f, 0.1f, 0.13f, 0.15f, 0.16f, 0.3f)
                curl(r, tip, -0.05f, -0.07f, -0.09f, -0.1f, -0.1f, -0.2f)
                fins(r, 1.28f, -0.05f, -0.02f, rise)
                fins(r, -0.1f, 0.55f, 0.3f, apex)
                fins(r, 0.9f, -0.3f, -0.1f, tip)
                r[TREMBLE] += 0.022f * punch + 0.006f * apex
                r[LOBE] = -0.2f * apex
                r[HEART] *= 1f + 0.3f * apex
                r[FLARE] += 0.3f * apex
                r[HALO] += 0.5f * apex
                r[WAVE] = 1f + 1.2f * rise - 0.7f * apex
                r[FINWAVE] = 1f - 0.85f * max(rise, apex)
                r[GLIDE] = 1f - max(rise, apex)
            }
            WhalePose.BLOWHOLE -> {
                // 0-20 Rücken wölbt sich, Blasloch auf; Stöße bei 22, 42, 62 (davor pumpt er sich jeweils auf)
                val k = env(a, 0f, 20f, 70f, 80f)
                var burst = 0f
                var charge = 0f
                for (p in BURSTS) {
                    burst += pulse(a, p, 3.5f)
                    charge += smooth(p - 18f, p - 1f, a) * (if (a < p) 1f else exp(-(a - p) / 1.5f))
                }
                r[ROOT_Y] = -3f * k + 5f * burst
                r[ROOT_PITCH] = 0.03f * k
                r[BLOAT] = 0.07f * charge - 0.03f * burst
                r[BLOW] = k * 0.85f + 0.45f * burst + 0.12f * sin(a * 1.9f) * burst
                r[NECK] = 0.07f * k - 0.03f * burst
                r[HEAD] = 0.1f * k - 0.08f * burst
                curl(r, k, -0.03f, -0.04f, -0.04f, -0.03f, -0.02f, -0.04f)
                curl(r, burst, 0.02f, 0.03f, 0.04f, 0.05f, 0.05f, 0.1f)
                fins(r, 0.3f, 0.2f, 0.1f, k)
                r[FL_FLAP] -= 0.4f * burst; r[FR_FLAP] -= 0.4f * burst
                r[TREMBLE] += 0.004f * k + 0.012f * burst
                r[JAW] = 0.1f * k + 0.18f * burst
                r[PLEAT] = 0.35f * charge
                r[HEART] *= 1f + 0.2f * k
                r[FLARE] += 0.3f * burst
                r[WAVE] = 1f - 0.65f * k
                r[FINWAVE] = 1f - 0.5f * k
            }
            WhalePose.DEVOUR -> {
                // 0-6 presst er das Maul zu und holt aus; 6-30 reißt der Kiefer in zwei Schüben auf (> 100°);
                // 30-100 Sog; 100-104 schnappt zu; 110-120 schluckt
                val coil = env(a, 0f, 5f, 7f, 13f)
                val open1 = smooth(6f, 16f, a)
                val open2 = smooth(18f, 30f, a)
                val snap = hit(100f, 104f, a)
                val held = 1f - snap
                val rest = smooth(108f, 120f, a)
                val suck = 0.5f + 0.5f * sin(a * 0.35f)
                val bite = pulse(a, 104f, 5f)
                r[JAW] = (1.15f * open1 + 0.72f * open2) * held + (0.04f * sin(a * 1.7f) + 0.03f * sin(a * 0.31f)) * open2 * held
                r[HEAD] = 0.08f * coil - (0.24f * open1 + 0.2f * open2) * held + 0.16f * bite + 0.012f * sin(a * 2.1f) * open2 * held
                r[HEAD_YAW] = 0.03f * sin(a * 0.23f) * open2 * held
                r[NECK] = 0.05f * coil - 0.16f * open1 * held
                r[ROOT_PITCH] = -0.06f * open1 * held
                r[ROOT_Z] = 5f * coil + 4f * open2 * held - 9f * env(a, 100f, 104f, 106f, 116f)
                r[THROAT] = 1f + (0.3f * open1 + 0.4f * open2 * (0.8f + 0.2f * suck)) * held
                r[PLEAT] = (0.4f * open1 + 0.65f * open2) * held * (0.85f + 0.15f * suck)
                r[BLOAT] = (0.05f * open1 + 0.06f * open2 * suck) * held
                r[TGLOW] = 0.7f + (0.6f * open1 + 0.9f * open2) * held
                r[TREMBLE] += 0.006f * open2 * held + 0.02f * bite
                // die Beule wandert den Hals hinab
                r[GULP_POS] = ((a - 107f) / 13f).coerceIn(0f, 1f)
                r[GULP_AMP] = 0.11f * smooth(104f, 108f, a) * (1f - smooth(118f, 120f, a))
                fins(r, -0.25f, 0.35f, 0.2f, open1 * held)
                fins(r, 1.0f, -0.2f, -0.1f, bite)
                curl(r, open1 * held, -0.03f, -0.04f, -0.05f, -0.06f, -0.06f, -0.12f)
                curl(r, bite, 0.03f, 0.04f, 0.05f, 0.06f, 0.06f, 0.12f)
                r[HALO] += 0.7f * open2 * held
                r[HEART] *= 1f + 0.3f * open2 * held
                r[FLARE] += 0.3f * open2 * held
                r[WAVE] = 1f - 0.75f * open1 * (1f - rest)
                r[FINWAVE] = 1f - 0.7f * open1 * (1f - rest)
            }
            WhalePose.REELING -> {
                // liegt auf der Seite, die Flanke klafft; ab REEL_UP rappelt er sich auf (der Server hebt ihn dann an)
                val k = 1f - smooth(REEL_UP, REEL_UP + 16f, a)
                val push = env(a, REEL_UP - 6f, REEL_UP + 4f, REEL_UP + 10f, REEL_UP + 24f)
                val tw = max(0f, sin(a * 0.19f + 0.4f)).pow(6)
                // alle ~6 s ringt er nach Luft
                val gasp = max(0f, sin(a * 0.0524f + 0.5f)).pow(6)
                val wheeze = sin(a * 0.31f)
                lie(r, k, k)
                r[HEAD] += (-0.14f * gasp + 0.03f * wheeze) * k - 0.25f * push
                r[NECK] = -0.06f * gasp * k - 0.12f * push
                r[JAW] = (0.2f + 0.6f * gasp + 0.05f * wheeze) * k + 0.9f * push
                r[THROAT] = 1f + (0.3f * gasp + 0.05f * wheeze) * k
                r[PLEAT] = (0.15f + 0.6f * gasp + 0.08f * wheeze) * k + 0.6f * push
                r[BLOAT] = (0.05f * gasp + 0.012f * wheeze) * k
                r[BLOW] = 0.7f * gasp * k
                r[S1 + 4] += 0.08f * tw * k
                r[S1 + 5] += 0.5f * tw * k
                r[LOBE] = -0.25f * tw * k
                r[FR_FLAP] += 0.3f * sin(a * 0.3f) * k
                r[FR_ELBOW] += 0.3f * sin(a * 0.3f - 0.9f) * k
                fins(r, 0.4f, -0.8f, -0.4f, push)
                // die Wunde lodert mit jedem Atemzug
                r[WOUND] = 1f + 0.08f * gasp
                r[RAYS] = 1f + 0.5f * gasp
                r[HEART] *= 1f + k * (0.6f + 0.2f * sin(a * 0.6f))
                r[FLARE] = lerp(r[FLARE], 1f, k)
                r[HALO] = 0.75f + 0.5f * gasp + 0.8f * push
                r[TREMBLE] += 0.004f * k + 0.012f * push
                r[TGLOW] = 0.7f + 0.5f * gasp + 0.6f * push
            }
            WhalePose.BACK_QUAKE -> {
                // 0-24 Hautwellen vom Kopf zur Fluke, immer stärker; 24-30 schnellt der Rücken hoch; 30-50 nachbeben
                val grow = smooth(0f, 24f, a)
                val quiver = grow * grow * (1f - smooth(30f, 48f, a))
                val dip = env(a, 18f, 23f, 24f, 26f)
                val jump = hit(24f, 27.5f, a) * (1f - smooth(30f, 40f, a))
                val after = osc(a, 30f, 0.7f, 6f)
                for (i in 0 until 6) r[S1 + i] += 0.035f * quiver * sin(a * 1.25f - i * 1.1f)
                r[NECK] = 0.03f * quiver * sin(a * 1.25f + 1.1f) - 0.04f * dip + 0.14f * jump
                r[HEAD] = 0.03f * quiver * sin(a * 1.25f + 2.2f) + 0.16f * jump
                r[TREMBLE] += 0.014f * quiver
                r[ROOT_Y] = 3f * dip - 10f * jump - 4f * after
                curl(r, dip, 0.03f, 0.03f, 0.02f, 0.02f, 0.02f, 0.03f)
                curl(r, jump, -0.14f, -0.1f, -0.07f, -0.05f, -0.04f, -0.12f)
                curl(r, after, 0.03f, 0.04f, 0.05f, 0.06f, 0.06f, 0.12f)
                fins(r, 0.2f, 0.3f, 0.1f, dip)
                fins(r, 0.95f, -0.42f, -0.25f, jump)
                r[FL_FLAP] += 0.05f * quiver * sin(a * 1.25f); r[FR_FLAP] += 0.05f * quiver * sin(a * 1.25f + 1f)
                r[BLOW] = 0.5f * jump
                r[JAW] = 0.22f * jump
                r[WAVE] = 1f - 0.85f * max(quiver, jump)
                r[FINWAVE] = 1f - 0.5f * quiver
            }
            WhalePose.TAIL_SWEEP -> {
                // dieselben Kurven wie die Fluken-Hitbox: 0-26 Skorpion, 26-46 fegt die Fluke über den Rücken nach vorn
                val back = 1f - smooth(46f, 56f, a)
                val rise = smooth(0f, 26f, a) * back
                val u = smooth(26f, 46f, a)
                for (i in 0 until 6) {
                    val key = if (u < 0.6f) lerp(SWEEP_A[i], SWEEP_M[i], u / 0.6f) else lerp(SWEEP_M[i], SWEEP_B[i], (u - 0.6f) / 0.4f)
                    r[S1 + i] += key * rise
                }
                r[TAILSCALE] = 1f + (if (u < 0.6f) 0.15f * u / 0.6f else 0.15f + 0.15f * (u - 0.6f) / 0.4f) * back
                // die Lappen spreizen sich wie eine Hand
                r[LOBE] = (-0.3f * smooth(8f, 26f, a) + 0.5f * env(a, 28f, 36f, 44f, 50f)) * back
                // Vorderleib und Flossen stemmen sich gegen den Schwung
                r[ROOT_PITCH] = 0.04f * rise - 0.05f * u * back
                r[NECK] = -0.05f * rise - 0.05f * u * back
                r[HEAD] = -0.06f * rise
                r[JAW] = 0.2f * env(a, 24f, 30f, 44f, 52f)
                fins(r, 0.75f, -0.38f, -0.2f, rise)
                r[TREMBLE] += 0.006f * env(a, 20f, 26f, 28f, 34f) + 0.01f * pulse(a, 46f, 4f)
                r[WAVE] = 1f - rise
                r[FINWAVE] = 1f - 0.6f * rise
            }
            WhalePose.GORGED -> {
                // träge, voll, von innen glühend; ab und zu ein Krampf
                val cramp = max(0f, sin(a * 0.07f)).pow(8)
                r[BLOAT] = 0.03f
                r[BLOAT_B] = 0.1f + 0.03f * sin(a * 0.11f) + 0.05f * cramp
                r[GORGE] = 1f
                r[THROAT] = 1.2f + 0.08f * sin(a * 0.2f)
                r[TGLOW] = 1.0f
                r[HEART] *= 1.25f
                r[FLARE] += 0.3f + 0.3f * cramp
                r[TREMBLE] += 0.007f * cramp
                r[JAW] = 0.05f + 0.32f * cramp
                r[HEAD] = 0.06f + 0.1f * cramp
                curl(r, cramp, -0.05f, -0.06f, -0.07f, -0.07f, -0.06f, -0.1f)
                r[ROOT_ROLL] = 0.06f * sin(a * 0.045f)
                fins(r, 0.5f, -0.5f, -0.25f, 1f)
                r[WAVE] = 0.55f
                r[FINWAVE] = 0.5f
            }
            WhalePose.BREAKOUT -> {
                // 0-20 drei Krämpfe, jeder heftiger, die rechte Flanke wölbt sich; 22 reißt sie auf; 24-60 sackt er
                // zusammen und kippt auf die Seite (der Server lässt ihn dabei auf die Plattform sinken)
                val cramp = 0.5f * bell(a, 3f, 3f) + 0.75f * bell(a, 10f, 3f) + bell(a, 16.5f, 3f)
                val tense = smooth(0f, 21f, a)
                val pre = 1f - smooth(21.5f, 23f, a)
                val shock = pulse(a, 22f, 5f)
                val cry = env(a, 22f, 23.5f, 32f, 46f)
                val sag = smooth(25f, 52f, a)
                val land = smooth(28f, 44f, a)
                r[BULGE] = (0.25f + 0.75f * tense + 0.25f * cramp + 0.06f * sin(a * 2.4f) * tense) * pre
                if (a >= 21.5f) {
                    r[WOUND] = hit(21.5f, 23.5f, a) * (1f + 0.45f * cos((a - 23.5f) * 0.7f) * exp(-max(0f, a - 23.5f) / 6f))
                    r[RAYS] = smooth(21.5f, 23f, a) * (1f + 1.2f * shock)
                } else {
                    r[WOUND] = 0f
                    r[RAYS] = 0f
                }
                r[TREMBLE] += (0.012f * tense + 0.02f * cramp) * pre + 0.03f * shock
                // krümmt sich um die Flanke: Kopf und Schwanz nach links, die rechte Seite spannt sich
                for (i in 0 until 6) r[Y1 + i] = 0.035f * cramp * pre
                r[HEAD_YAW] = -0.2f * cramp * pre + 0.25f * cry
                curl(r, cramp * pre, -0.05f, -0.07f, -0.09f, -0.1f, -0.1f, -0.15f)
                r[JAW] = 0.4f * cramp * pre + 1.2f * cry + 0.24f * sag
                r[HEAD] = 0.1f * cramp * pre - 0.3f * cry
                r[NECK] = -0.15f * cry
                r[PLEAT] = 0.3f * cramp * pre + 0.7f * cry
                // der Riss wirft ihn nach links
                r[ROOT_X] = 9f * shock
                lie(r, sag, land)
                r[ROOT_ROLL] -= 0.25f * shock
                r[SQUASH] += 0.05f * pulse(a, 50f, 4f)
                r[FL_FLAP] += 0.5f * shock * sin(a * 1.3f)
                r[FR_FLAP] += 0.6f * shock * sin(a * 1.3f + 1f) + 0.4f * cry
                curl(r, osc(a, 22f, 0.6f, 6f), 0.02f, 0.04f, 0.06f, 0.08f, 0.1f, 0.25f)
                r[BLOAT] = (0.05f * tense + 0.04f * cramp) * pre
                r[HEART] *= 1f + 0.8f * shock + 0.5f * sag
                r[FLARE] = lerp(r[FLARE], 1f, smooth(21f, 24f, a))
                r[HALO] += 0.5f * cramp * pre + 0.8f * shock
                r[WAVE] *= 1f - tense
                r[FINWAVE] *= 1f - tense
            }
            WhalePose.FLOOD -> {
                // 0-30 holt der Schwanz nach links aus (weg von der Arena), Fluke hochkant; 30-36 peitscht er nach
                // rechts durch – dorthin läuft die Welle; 36-70 nachschwingen, der Kopf schaut ihr nach
                val lift = smooth(0f, 30f, a)
                val swing = hit(30f, 36f, a)
                val hold = 1f - smooth(40f, 70f, a)
                val ring = osc(a, 36f, 0.42f, 9f)
                val side = (lift * (1f - swing) - 1.15f * swing) * hold + 0.25f * ring
                for (i in 0 until 6) r[Y1 + i] = FLOOD_SHARE[i] * side
                r[FLUKE_ROLL] = 1.45f * smooth(4f, 22f, a) * (1f - smooth(44f, 62f, a))
                curl(r, lift * (1f - swing) * hold, 0.03f, 0.04f, 0.05f, 0.06f, 0.06f, 0.1f)
                // der Rumpf hält gegen und legt sich in den Schlag
                r[ROOT_YAW] = (-0.08f * lift * (1f - swing) + 0.12f * swing) * hold
                r[ROOT_ROLL] = (0.12f * lift * (1f - swing) - 0.25f * swing) * hold
                r[HEAD_YAW] = -0.15f * lift * (1f - swing) * hold + 0.4f * env(a, 34f, 46f, 60f, 70f)
                r[LOBE] = 0.35f * swing * (1f - smooth(36f, 42f, a))
                r[JAW] = 0.4f * env(a, 30f, 34f, 40f, 54f)
                fins(r, 0.2f, 0.3f, 0.15f, lift * hold)
                r[FL_FLAP] += 0.3f * lift * (1f - swing) * hold
                r[FR_FLAP] -= 0.3f * swing * hold
                r[TREMBLE] += 0.01f * pulse(a, 36f, 5f)
                r[WAVE] = 1f - max(lift, swing) * hold
            }
        }
        return r
    }

    /** Dauer der Überblendung in die neue Haltung. */
    fun blendTicks(from: Int, to: Int): Float = when {
        to == WhalePose.STAGGER -> 2f
        to == WhalePose.BREAKOUT || to == WhalePose.BARREL_ROLL || to == WhalePose.BACK_QUAKE -> 4f
        from == WhalePose.BREAKOUT && to == WhalePose.REELING -> 8f
        from == WhalePose.REELING -> 12f
        to == WhalePose.SWALLOW || to == WhalePose.TAIL_SLAM || to == WhalePose.DEVOUR || to == WhalePose.ROAR -> 5f
        from == WhalePose.BEACHED || to == WhalePose.DEATH -> 12f
        // zurück ins Gleiten ohne Eile
        to == WhalePose.SWIM || to == WhalePose.GORGED -> 14f
        else -> 9f
    }

    private fun smooth(from: Float, to: Float, x: Float): Float {
        val k = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /** Schlag: beschleunigt bis zum Ende statt weich auszulaufen. */
    private fun hit(from: Float, to: Float, x: Float): Float {
        val k = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return k * k
    }

    /** Hüllkurve: steigt zwischen [in0] und [in1], fällt zwischen [out0] und [out1]. */
    private fun env(x: Float, in0: Float, in1: Float, out0: Float, out1: Float) = smooth(in0, in1, x) * (1f - smooth(out0, out1, x))

    /** Stoß bei [at], klingt mit [decay] Ticks ab. */
    private fun pulse(x: Float, at: Float, decay: Float) = if (x < at) 0f else exp(-(x - at) / decay)

    /** Nachschwingen ab [at]: gedämpfte Schwingung. */
    private fun osc(x: Float, at: Float, rate: Float, decay: Float) = if (x < at) 0f else sin((x - at) * rate) * exp(-(x - at) / decay)

    /** Glocke um [at] mit Breite [width]. */
    private fun bell(x: Float, at: Float, width: Float): Float {
        val d = (x - at) / width
        return exp(-d * d)
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    const val RIG_SIZE = 62

    const val TAU = (2.0 * PI).toFloat()

    const val ROOT_PITCH = 0
    const val ROOT_YAW = 1
    const val ROOT_ROLL = 2
    const val ROOT_X = 3
    const val ROOT_Y = 4
    const val ROOT_Z = 5
    const val SCALE = 6
    const val SQUASH = 7 // vom eigenen Gewicht plattgedrückt (gestrandet)
    const val NECK = 8
    const val HEAD = 9
    const val HEAD_YAW = 10
    const val HEAD_ROLL = 11
    const val JAW = 12
    const val S1 = 13 // Neigung: Bauch, Hüfte, Schwanz 1-3, Fluke
    const val Y1 = 19 // Seitwärts: dieselben sechs
    const val G1 = 25 // Wachstum beim Auftritt: dieselben sechs
    const val TWIST = 31 // Verdrehung des Hinterleibs um die Längsachse
    const val TAILSCALE = 32 // der Schwanz streckt sich (Schwanzpeitsche)
    const val LOBE = 33
    const val FLUKE_ROLL = 34
    const val FL_SWEEP = 35
    const val FL_FLAP = 36
    const val FL_TWIST = 37
    const val FL_ELBOW = 38
    const val FR_SWEEP = 39
    const val FR_FLAP = 40
    const val FR_TWIST = 41
    const val FR_ELBOW = 42
    const val BLOAT = 43
    const val BLOAT_B = 44 // nur der Bauch (verschluckte Spieler)
    const val GULP_POS = 45 // Beule beim Schlucken: 0 Kehlsack … 1 Bauch
    const val GULP_AMP = 46
    const val THROAT = 47
    const val PLEAT = 48 // Kehlfalten: 0 zu, 1 weit offen
    const val TGLOW = 49
    const val BLOW = 50 // Blasloch-Lappen: 0 zu, 1 offen
    const val HEART = 51
    const val FLARE = 52
    const val HALO = 53 // Lichthof der Augen
    const val TREMBLE = 54
    const val WAVE = 55
    const val FINWAVE = 56
    const val GLIDE = 57 // wie sehr er sich im Sinken tragen lässt (0 = schlägt durch)
    const val BULGE = 58 // Flanke wölbt sich vor dem Ausbruch
    const val WOUND = 59 // Flanke aufgerissen: 0 heil, 1 Lappen offen
    const val RAYS = 60
    const val GORGE = 61 // Bauchglühen
    const val COUNT = RIG_SIZE

    val WAVE_AMP = floatArrayOf(0.035f, 0.05f, 0.07f, 0.085f, 0.1f, 0.17f)

    /** Anteil jedes Gelenks an der Verdrehung des Hinterleibs. */
    val TWIST_SHARE = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0f)

    // Gestrandet: Rollwinkel, Stauchung, Durchhang je Gelenk (abgestimmt auf LeviathanBody.BEACHED_PROFILE)
    const val LIE_ROLL = 0.95f
    const val LIE_SQUASH = 0.34f
    val LIE_SAG = floatArrayOf(0f, 0.02f, 0.05f, 0.1f, 0.14f)

    /** Taumeln: ab hier rappelt er sich auf (Server: `reeling()` hebt ihn in den letzten 30 Ticks an). */
    val REEL_UP = WhalePose.duration(WhalePose.REELING) - 30f

    val BURSTS = floatArrayOf(22f, 42f, 62f)

    // Schwanzpeitsche: Gelenkwinkel am Ende des Aufrichtens (Tick 26), mitten im Fegen und ganz vorn (Tick 46)
    val SWEEP_A = floatArrayOf(0.436f, 0.262f, 0.175f, 0.087f, 0f, 0.785f)
    val SWEEP_M = floatArrayOf(0.698f, 0.611f, 0.576f, 0.524f, 0.384f, 0.436f)
    val SWEEP_B = floatArrayOf(0.873f, 0.785f, 0.698f, 0.524f, 0.262f, 0.175f)

    val FLOOD_SHARE = floatArrayOf(0.06f, 0.1f, 0.14f, 0.17f, 0.19f, 0.22f)
}
