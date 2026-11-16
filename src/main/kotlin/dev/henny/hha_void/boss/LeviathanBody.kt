package dev.henny.hha_void.boss

import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Körper des Leviathans für Server und Client: Hitboxen, Rückenfläche zum Draufstehen, Maul/Herz/Blasloch.
 *
 * Alles wird aus den **echten Modellknochen** gerechnet (Vorwärtskinematik wie `ModelPart`): dieselben Drehpunkte wie
 * `tools/textures/GenWhale.java`, dieselben Gelenkwinkel wie das Modell ([LeviathanRig] = Kopie von
 * `VoidBossModel.rig`), dieselbe Biegung in Kurven/Steigflug ([Motion], deterministisch aus der Lage der letzten
 * Ticks – der Renderer zeichnet mit genau diesen Werten). Darum liegen die 38 kleinen Boxen ([VOLUMES]) auch bei Neigung,
 * Schräglage, Rolle und in allen Haltungen (Kiefer offen, Fluke oben, gestrandet …) auf dem sichtbaren Körper.
 *
 * Einheiten: Modell-Pixel (1 px = 0,3 Blöcke bei Skalierung [RENDER_SCALE]), +Y unten, Blick −Z, links = +X.
 * Welt: `pos` = Entity-Position, Körpermitte (Modell-Ursprung) [BODY_UP] darüber.
 */
object LeviathanBody {

    /** Vergrößerung gegenüber dem alten Sternenwal (Renderer-Skalierung 2 → 4,8). */
    const val K = 2.4

    /** Skalierung im Renderer: 8 Modelleinheiten = 1 Block bei Skalierung 1 → Wal ≈ 61 Blöcke lang. */
    const val RENDER_SCALE = 4.8f

    /** Blöcke je Modell-Pixel. */
    const val PX = RENDER_SCALE / 16.0

    /** Körpermitte über der Entity-Position (Modell-Ursprung 1,501 Blöcke × Skalierung). */
    const val BODY_UP = 1.501 * RENDER_SCALE

    // Grobe Maße (Ruhelage, nur noch für Abstände/Kameras – Angriffe nehmen die Punkte aus [Frame])
    const val MOUTH_FORWARD = 27.0
    const val MOUTH_UP = -2.4
    const val HEART_FORWARD = 3.0
    const val HEART_UP = -9.3
    const val FLUKE_BACK = 29.0
    const val BLOWHOLE_FORWARD = 10.5
    const val BLOWHOLE_UP = 8.1
    const val LENGTH = 61.0
    const val DIAMETER = 16.2

    // Arten der Volumen (Schadensfaktoren)
    const val HEAD = 0
    const val BODY = 1
    const val BLOWHOLE = 2
    const val HEART = 3
    const val TAIL = 4
    const val FLUKE = 5
    const val FIN = 6
    const val JAW = 7

    /** Gestrandet liegt die Körpermitte so hoch über dem Boden (das Modell rollt/staucht sich darauf ab). */
    const val BEACHED_CENTER = 5.0
    const val BEACH_LIFT = BEACHED_CENTER - BODY_UP

    fun isBeachedPose(pose: Int) = pose == WhalePose.BEACHED || pose == WhalePose.REELING

    // ------------------------------------------------------------------------------------------------ Knochen

    /** Knochen in der Reihenfolge der Kette (Eltern stehen vorher). Drehpunkte absolut in der Ruhelage (px). */
    private class Bone(val name: String, val parent: Int, val x: Double, val y: Double, val z: Double)

    const val B_ROOT = 0
    const val B_BODY = 1
    const val B_CHEST = 2
    const val B_NECK = 3
    const val B_HEAD = 4
    const val B_JAW = 5
    const val B_BELLY = 6
    const val B_HIP = 7
    const val B_TAIL1 = 8
    const val B_TAIL2 = 9
    const val B_TAIL3 = 10
    const val B_FLUKE = 11
    const val B_FLUKE_L = 12
    const val B_FLUKE_L2 = 13
    const val B_FLUKE_R = 14
    const val B_FLUKE_R2 = 15
    const val B_FIN_L = 16
    const val B_FIN_L2 = 17
    const val B_FIN_L3 = 18
    const val B_FIN_R = 19
    const val B_FIN_R2 = 20
    const val B_FIN_R3 = 21
    const val B_HEART = 22

    private val BONES = arrayOf(
        Bone("root", -1, 0.0, 0.0, 0.0),
        Bone("body", B_ROOT, 0.0, 0.0, 0.0),
        Bone("chest", B_BODY, 0.0, 0.0, 0.0),
        Bone("neck", B_CHEST, 0.0, 0.0, -20.0),
        Bone("head", B_NECK, 0.0, -2.0, -44.0),
        Bone("jaw", B_HEAD, 0.0, 8.0, -40.0),
        Bone("belly", B_CHEST, 0.0, 0.0, 12.0),
        Bone("hip", B_BELLY, 0.0, -1.0, 30.0),
        Bone("tail1", B_HIP, 0.0, -2.0, 46.0),
        Bone("tail2", B_TAIL1, 0.0, -3.0, 60.0),
        Bone("tail3", B_TAIL2, 0.0, -3.0, 72.0),
        Bone("fluke", B_TAIL3, 0.0, -3.0, 84.0),
        Bone("fluke_l", B_FLUKE, 5.0, -3.0, 86.0),
        Bone("fluke_l2", B_FLUKE_L, 22.0, -3.5, 94.0),
        Bone("fluke_r", B_FLUKE, -5.0, -3.0, 86.0),
        Bone("fluke_r2", B_FLUKE_R, -22.0, -3.5, 94.0),
        Bone("fin_l", B_CHEST, 25.0, 13.0, -12.0),
        Bone("fin_l2", B_FIN_L, 41.0, 13.0, -12.0),
        Bone("fin_l3", B_FIN_L2, 54.0, 13.0, -12.0),
        Bone("fin_r", B_CHEST, -25.0, 13.0, -12.0),
        Bone("fin_r2", B_FIN_R, -41.0, 13.0, -12.0),
        Bone("fin_r3", B_FIN_R2, -54.0, 13.0, -12.0),
        Bone("heart", B_CHEST, 0.0, 27.0, -10.0),
    )
    private val BONE_COUNT = BONES.size

    /** Wirbel des Hinterleibs (Bauch, Hüfte, Schwanz 1-3, Fluke) – Index wie die Rig-Kanäle S1..S6. */
    private val SPINE = intArrayOf(B_BELLY, B_HIP, B_TAIL1, B_TAIL2, B_TAIL3, B_FLUKE)

    // ------------------------------------------------------------------------------------------------ Volumen

    /**
     * Ein Hitbox-Volumen: Quader an Knochen [bone], Mitte/halbe Kanten in der Ruhelage (absolut, px).
     * [round] = rundlicher Querschnitt (Rumpf: quer Ellipse, längs Quader), sonst flache Platte (Flossen, Fluke).
     * [walk] = Oberseite ist begehbar (Rückenfläche).
     */
    class Volume(
        val name: String, val kind: Int, val bone: Int,
        val cx: Double, val cy: Double, val cz: Double,
        val hx: Double, val hy: Double, val hz: Double,
        val round: Boolean = true, val walk: Boolean = true,
    )

    val VOLUMES: List<Volume> = listOf(
        // Kopf (Kuppel bis zur Lippe y 5) – vorn nach hinten
        Volume("head0", HEAD, B_HEAD, 0.0, -7.0, -84.0, 20.0, 12.0, 4.0),
        Volume("head1", HEAD, B_HEAD, 0.0, -9.0, -75.5, 23.0, 14.0, 4.5),
        Volume("head2", HEAD, B_HEAD, 0.0, -9.5, -66.5, 23.5, 14.5, 4.5),
        Volume("head3", HEAD, B_HEAD, 0.0, -10.0, -57.0, 24.0, 15.0, 5.0),
        Volume("head4", HEAD, B_HEAD, 0.0, -10.3, -47.0, 24.5, 15.3, 5.0),
        // Unterkiefer mit Kehlsack (klappt mit dem Kiefer auf)
        Volume("jaw0", JAW, B_JAW, 0.0, 14.5, -90.5, 12.5, 6.5, 4.5, walk = false),
        Volume("jaw1", JAW, B_JAW, 0.0, 16.0, -81.5, 15.5, 9.0, 4.5, walk = false),
        Volume("jaw2", JAW, B_JAW, 0.0, 17.5, -71.5, 18.0, 10.5, 5.5, walk = false),
        Volume("jaw3", JAW, B_JAW, 0.0, 17.5, -59.5, 19.5, 10.5, 6.5, walk = false),
        Volume("jaw4", JAW, B_JAW, 0.0, 17.5, -46.5, 20.0, 10.5, 6.5, walk = false),
        // Hals, Brust, Bauch
        Volume("neck0", BODY, B_NECK, 0.0, 0.5, -37.5, 25.0, 26.5, 6.5),
        Volume("neck1", BODY, B_NECK, 0.0, 0.5, -24.5, 25.0, 26.5, 6.5),
        Volume("chest0", BODY, B_CHEST, 0.0, 0.0, -16.0, 26.0, 27.0, 6.0),
        Volume("chest1", BODY, B_CHEST, 0.0, 0.0, -4.0, 26.0, 27.0, 6.0),
        Volume("chest2", BODY, B_CHEST, 0.0, 0.0, 8.0, 26.0, 27.0, 6.0),
        Volume("belly0", BODY, B_BELLY, 0.0, 0.0, 15.0, 25.0, 26.0, 5.0),
        Volume("belly1", BODY, B_BELLY, 0.0, -0.5, 25.0, 23.0, 23.5, 5.0),
        // Hüfte und Schwanzstiel
        Volume("hip0", BODY, B_HIP, 0.0, -1.0, 32.5, 21.0, 21.0, 4.5),
        Volume("hip1", BODY, B_HIP, 0.0, -2.0, 41.5, 19.5, 19.0, 4.5),
        Volume("tail0", TAIL, B_TAIL1, 0.0, -2.0, 48.0, 17.0, 17.0, 4.0),
        Volume("tail1", TAIL, B_TAIL1, 0.0, -2.5, 56.0, 14.0, 14.5, 4.0),
        Volume("tail2", TAIL, B_TAIL2, 0.0, -3.0, 61.5, 12.0, 12.0, 3.5),
        Volume("tail3", TAIL, B_TAIL2, 0.0, -3.0, 68.5, 10.0, 10.0, 3.5),
        Volume("tail4", TAIL, B_TAIL3, 0.0, -3.0, 74.0, 8.0, 8.0, 4.0),
        Volume("tail5", TAIL, B_TAIL3, 0.0, -3.0, 82.0, 6.0, 6.0, 4.0),
        // Fluke: Mittelstück und zwei Lappen je Seite
        Volume("fluke", FLUKE, B_FLUKE, 0.0, -3.0, 89.5, 7.0, 4.0, 7.5, round = false),
        Volume("fluke_l", FLUKE, B_FLUKE_L, 13.5, -3.5, 94.0, 8.5, 2.5, 10.0, round = false),
        Volume("fluke_l2", FLUKE, B_FLUKE_L2, 29.5, -3.5, 99.0, 7.5, 1.5, 9.0, round = false),
        Volume("fluke_r", FLUKE, B_FLUKE_R, -13.5, -3.5, 94.0, 8.5, 2.5, 10.0, round = false),
        Volume("fluke_r2", FLUKE, B_FLUKE_R2, -29.5, -3.5, 99.0, 7.5, 1.5, 9.0, round = false),
        // Brustflossen: drei Glieder je Seite
        Volume("fin_l", FIN, B_FIN_L, 32.0, 13.0, -12.0, 9.0, 2.0, 10.0, round = false),
        Volume("fin_l2", FIN, B_FIN_L2, 46.5, 13.0, -12.5, 7.5, 1.5, 7.5, round = false),
        Volume("fin_l3", FIN, B_FIN_L3, 59.5, 13.0, -12.0, 7.5, 1.0, 5.0, round = false),
        Volume("fin_r", FIN, B_FIN_R, -32.0, 13.0, -12.0, 9.0, 2.0, 10.0, round = false),
        Volume("fin_r2", FIN, B_FIN_R2, -46.5, 13.0, -12.5, 7.5, 1.5, 7.5, round = false),
        Volume("fin_r3", FIN, B_FIN_R3, -59.5, 13.0, -12.0, 7.5, 1.0, 5.0, round = false),
        // Schwachstellen
        Volume("heart", HEART, B_HEART, 0.0, 31.5, -10.0, 7.0, 7.5, 7.0, walk = false),
        Volume("blowhole", BLOWHOLE, B_NECK, 0.0, -27.0, -35.0, 7.0, 1.5, 6.0, round = false),
    )

    val COUNT = VOLUMES.size
    val NAMES: List<String> = VOLUMES.map { it.name }
    fun indexOf(name: String) = NAMES.indexOf(name)

    /** Ist das Volumen in dieser Haltung da? (nichts vor dem Auftritt) */
    fun active(index: Int, pose: Int): Boolean = pose != WhalePose.HIDDEN

    /** Schadensfaktor je Art; gestrandet/taumelnd liegt das Herz offen. */
    fun damageFactor(kind: Int, exposed: Boolean): Float = when (kind) {
        BLOWHOLE -> 1.5f
        HEART -> if (exposed) 2.0f else 0.8f
        FIN, FLUKE -> 0.75f
        TAIL -> 0.9f
        else -> 1f
    }

    // ------------------------------------------------------------------------------------------------ Bewegung

    /**
     * Was der Flugbahn folgt (Neigung, Schräglage, vorauseilender Kopf, nachziehender Hinterleib, Schlagwelle):
     * deterministisch aus den letzten Ticks berechnet – Server (Hitboxen) und Renderer (Bild) nutzen dieselben Werte.
     */
    class Motion {
        var bank = 0f
        var leadYaw = 0f
        var leadPitch = 0f
        val bendYaw = FloatArray(6)
        val bendPitch = FloatArray(6)
        var swimPhase = 0f
        var climb = 0f
        var finLag = 0f
        var speed = 0f

        fun copyFrom(o: Motion) {
            bank = o.bank; leadYaw = o.leadYaw; leadPitch = o.leadPitch; swimPhase = o.swimPhase
            climb = o.climb; finLag = o.finLag; speed = o.speed
            o.bendYaw.copyInto(bendYaw); o.bendPitch.copyInto(bendPitch)
        }

        /** Zwischenwert für den Renderer ([k] = tickDelta). */
        fun lerp(a: Motion, b: Motion, k: Float): Motion {
            fun l(x: Float, y: Float) = x + (y - x) * k
            bank = l(a.bank, b.bank); leadYaw = l(a.leadYaw, b.leadYaw); leadPitch = l(a.leadPitch, b.leadPitch)
            climb = l(a.climb, b.climb); finLag = l(a.finLag, b.finLag); speed = l(a.speed, b.speed)
            var dp = b.swimPhase - a.swimPhase
            if (dp < -PI.toFloat()) dp += 4f * PI.toFloat()
            swimPhase = a.swimPhase + dp * k
            for (i in 0 until 6) { bendYaw[i] = l(a.bendYaw[i], b.bendYaw[i]); bendPitch[i] = l(a.bendPitch[i], b.bendPitch[i]) }
            return this
        }
    }

    /** Lage eines Ticks (Verlauf für [motion]). */
    class Sample(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val swim: Float)

    /** Abstand der Gelenke Bauch, Hüfte, Schwanz 1-3, Fluke zum jeweils vorderen, in Blöcken. */
    private val SEGMENT_LENGTH = floatArrayOf(3.6f, 5.4f, 4.8f, 4.2f, 3.6f, 3.6f)
    private const val RAD = (PI / 180.0).toFloat()

    /**
     * [Motion] aus dem Verlauf [h] (älteste zuerst), ausgewertet am Eintrag [at]. Gleichmäßige Drehrate über 8 Ticks
     * (glättet beim Client die in 1,4°-Stufen übertragenen Winkel), Tempo über 4 Ticks.
     */
    fun motion(h: List<Sample>, at: Int, out: Motion): Motion {
        val cur = h.getOrNull(at) ?: return out.also { it.bendYaw.fill(0f); it.bendPitch.fill(0f); it.bank = 0f; it.leadYaw = 0f; it.leadPitch = 0f }
        val back = min(8, at)
        val old = h[at - back]
        val sp = h[at - min(4, at)]
        val n = max(1, back)
        var turn = 0f
        for (i in at - back + 1..at) turn += MathHelper.wrapDegrees(h[i].yaw - h[i - 1].yaw)
        val yawRate = turn / n
        val pitchRate = (cur.pitch - old.pitch) / n
        val ns = max(1, min(4, at))
        val dx = cur.x - sp.x; val dy = cur.y - sp.y; val dz = cur.z - sp.z
        val speed = (sqrt(dx * dx + dy * dy + dz * dz) / ns).toFloat()
        val vy = (dy / ns).toFloat()
        val delay = 1.25f / max(speed, 0.45f)
        for (i in 0 until 6) {
            val tau = SEGMENT_LENGTH[i] * delay
            out.bendYaw[i] = limit(-yawRate * tau * RAD, 0.05f, 0.1f)
            out.bendPitch[i] = limit(-pitchRate * tau * RAD, 0.06f, 0.12f)
        }
        out.leadYaw = limit(yawRate * RAD * 4.5f, 0.15f, 0.3f)
        out.leadPitch = limit(pitchRate * RAD * 4f, 0.1f, 0.22f)
        out.bank = limit(yawRate * 0.12f, 0.18f, 0.34f) * (speed / 0.5f).coerceIn(0.3f, 1f)
        out.climb = (vy / 0.2f).coerceIn(-1f, 1f)
        out.finLag = (-vy * 0.45f).coerceIn(-0.2f, 0.3f)
        out.speed = speed
        out.swimPhase = cur.swim
        return out
    }

    /** Takt der Schlagwelle je Tick (wie im Renderer): ruhig im Gleiten, schnell im Sturzflug. */
    fun swimRate(speed: Float, climb: Float, diving: Boolean): Float {
        val up = max(climb, 0f)
        val dive = if (diving) 1f else 0f
        val down = max(-climb, 0f) * (1f - dive)
        return (0.07f + 0.06f * speed) * (1f + 0.7f * dive) * (1f - 0.2f * up - 0.3f * down)
    }

    /** Lässt Werte bis [free] unverändert durch und läuft darüber weich gegen [most] aus. */
    fun limit(x: Float, free: Float, most: Float): Float {
        val a = abs(x)
        if (a <= free) return x
        val over = most - free
        return sign(x) * (free + over * tanh((a - free) / over))
    }

    // ------------------------------------------------------------------------------------------------ Lage

    /** Alles, was die Körperlage eines Ticks bestimmt. */
    class State(
        val pos: Vec3d, val yaw: Float, val pitch: Float,
        val pose: Int, val poseAge: Float, val previous: Int, val previousAge: Float,
        val age: Float, val phase: Int, val motion: Motion,
    )

    /**
     * Ausgewertete Körperlage: Weltmatrix je Knochen (3×3 linear inkl. Skalierung → Blöcke je px, plus Ursprung des
     * Knochens in der Welt).
     */
    class Frame internal constructor(val state: State) {
        /** je Knochen: 9 lineare Werte (Zeilen) + 3 Verschiebung. */
        internal val m = Array(BONE_COUNT) { DoubleArray(12) }
        val boxes = arrayOfNulls<Box>(COUNT)

        /** Welt-Mitte jedes Volumens. */
        val centers = arrayOfNulls<Vec3d>(COUNT)

        /** Punkt [x],[y],[z] (Ruhelage, absolut px) am Knochen [bone] in der Welt. */
        fun point(bone: Int, x: Double, y: Double, z: Double): Vec3d {
            val b = BONES[bone]
            val a = m[bone]
            val lx = x - b.x; val ly = y - b.y; val lz = z - b.z
            return Vec3d(
                a[0] * lx + a[1] * ly + a[2] * lz + a[9],
                a[3] * lx + a[4] * ly + a[5] * lz + a[10],
                a[6] * lx + a[7] * ly + a[8] * lz + a[11],
            )
        }

        /** Richtung [x],[y],[z] (Modellraum) am Knochen [bone] in der Welt, normiert. */
        fun direction(bone: Int, x: Double, y: Double, z: Double): Vec3d {
            val a = m[bone]
            return Vec3d(a[0] * x + a[1] * y + a[2] * z, a[3] * x + a[4] * y + a[5] * z, a[6] * x + a[7] * y + a[8] * z).normalize()
        }

        /** Mitte der Maulöffnung (zwischen Oberlippe und Unterkiefer, vorn). */
        fun mouth(): Vec3d = point(B_HEAD, 0.0, 6.0, -80.0).add(point(B_JAW, 0.0, 12.0, -84.0)).multiply(0.5)

        /** Wie weit das Maul offen ist (Abstand Oberlippe ↔ Unterkiefer vorn, Blöcke). */
        fun gape(): Double = point(B_HEAD, 0.0, 5.0, -84.0).distanceTo(point(B_JAW, 0.0, 9.0, -88.0))

        /** Blickrichtung des Kopfes (Welt). */
        fun headForward(): Vec3d = direction(B_HEAD, 0.0, 0.0, -1.0)

        fun heart(): Vec3d = point(B_HEART, 0.0, 31.0, -10.0)
        fun blowhole(): Vec3d = point(B_NECK, 0.0, -27.0, -35.0)
        fun fluke(): Vec3d = point(B_FLUKE, 0.0, -3.0, 95.0)
        fun center(): Vec3d = point(B_CHEST, 0.0, 0.0, 0.0)

        /** Rücken (Mitte der Brust, Oberseite). */
        fun back(): Vec3d = point(B_CHEST, 0.0, -27.0, 0.0)

        /** Flanke rechts (Ausbruchwunde). */
        fun flank(): Vec3d = point(B_CHEST, -26.0, 0.0, -2.0)

        /** Punkt auf der Längsachse: [along] ∈ 0 (Maul) … 1 (Fluke). */
        fun spine(along: Double): Vec3d {
            val keys = SPINE_KEYS
            val k = along.coerceIn(0.0, 1.0) * (keys.size - 1)
            val i = min(keys.size - 2, k.toInt())
            val f = k - i
            val a = point(keys[i].first, 0.0, keys[i].second, keys[i].third)
            val b = point(keys[i + 1].first, 0.0, keys[i + 1].second, keys[i + 1].third)
            return a.lerp(b, f)
        }

        /** Alle aktiven Boxen. */
        fun activeBoxes(): List<Box> = boxes.filterNotNull()

        /** Boxen für den Geländeschutz: ohne das hängende Herz und die dünnen Flossen (dürfen den Boden streifen). */
        fun terrainBoxes(skipJaw: Boolean = false): List<Box> = (0 until COUNT).mapNotNull { i ->
            val k = VOLUMES[i].kind
            if (k == HEART || k == FIN || (skipJaw && k == JAW)) null else boxes[i]
        }

        // ---- Rückenfläche

        /**
         * Höchste begehbare Oberfläche unter (bzw. knapp über) [p]: Welt-Höhe, Volumen-Index und wie steil sie ist
         * (Aufwärts-Anteil der Flächennormale, 1 = waagerecht). null, wenn [p] über keinem begehbaren Teil liegt.
         * [reach] = wie weit die Fläche über den Füßen liegen darf (Hochsteigen) bzw. darunter (Stehen).
         */
        fun surface(p: Vec3d, above: Double = 1.2, below: Double = 64.0): Surface? {
            var best: Surface? = null
            for (i in 0 until COUNT) {
                val v = VOLUMES[i]
                if (!v.walk) continue
                val box = boxes[i] ?: continue
                if (p.x < box.minX - 0.4 || p.x > box.maxX + 0.4 || p.z < box.minZ - 0.4 || p.z > box.maxZ + 0.4) continue
                val s = surfaceOn(i, p) ?: continue
                if (s.y > p.y + above || s.y < p.y - below) continue
                if (best == null || s.y > best.y) best = s
            }
            return best
        }

        /**
         * Oberfläche des Volumens [i] senkrecht über/unter [p]: die Welt-Senkrechte durch [p] wird in den Knochenraum
         * gebracht und mit dem Querschnitt geschnitten (Ellipse quer zur Längsachse, bei Platten die nach oben zeigende
         * Seite) – exakt auch, wenn der Körper geneigt, gerollt oder gestaucht ist.
         */
        private fun surfaceOn(i: Int, p: Vec3d): Surface? {
            val v = VOLUMES[i]
            val a = m[v.bone]
            val inv = invert(a) ?: return null
            val c = centers[i] ?: return null
            val dx = p.x - c.x; val dy = p.y - c.y; val dz = p.z - c.z
            // Punkt und Welt-Oben im Volumen-Raum (px, relativ zur Mitte)
            val px = inv[0] * dx + inv[1] * dy + inv[2] * dz
            val py = inv[3] * dx + inv[4] * dy + inv[5] * dz
            val pz = inv[6] * dx + inv[7] * dy + inv[8] * dz
            val ux = inv[1]; val uy = inv[4]; val uz = inv[7]
            val t: Double
            val nx: Double
            val ny: Double
            if (v.round) {
                // (px + t ux)²/hx² + (py + t uy)²/hy² = 1 → obere Lösung (größtes t)
                val ax = 1.0 / (v.hx * v.hx); val ay = 1.0 / (v.hy * v.hy)
                val qa = ux * ux * ax + uy * uy * ay
                if (qa < 1e-12) return null
                val qb = 2 * (px * ux * ax + py * uy * ay)
                val qc = px * px * ax + py * py * ay - 1.0
                val disc = qb * qb - 4 * qa * qc
                if (disc < 0) return null
                t = (-qb + sqrt(disc)) / (2 * qa)
                nx = (px + t * ux) * ax; ny = (py + t * uy) * ay
            } else {
                // die Platten-Seite, deren Normale nach oben zeigt
                if (abs(uy) < 1e-9) return null
                val face = if (uy < 0) -v.hy else v.hy
                t = (face - py) / uy
                val lx = px + t * ux
                if (abs(lx) > v.hx) return null
                nx = 0.0; ny = if (uy < 0) -1.0 else 1.0
            }
            val lz = pz + t * uz
            if (abs(lz) > v.hz + 0.5) return null
            val nWorld = Vec3d(a[0] * nx + a[1] * ny, a[3] * nx + a[4] * ny, a[6] * nx + a[7] * ny).normalize()
            return Surface(i, p.y + t, nWorld.y)
        }
    }

    class Surface(val volume: Int, val y: Double, val up: Double)

    /** Punkte auf der Längsachse von vorn nach hinten (Knochen, y, z). */
    private val SPINE_KEYS = listOf(
        Triple(B_HEAD, -2.0, -86.0), Triple(B_HEAD, -4.0, -60.0), Triple(B_NECK, 0.0, -32.0), Triple(B_CHEST, 0.0, -4.0),
        Triple(B_BELLY, 0.0, 20.0), Triple(B_HIP, -1.0, 38.0), Triple(B_TAIL1, -2.0, 52.0), Triple(B_TAIL2, -3.0, 66.0),
        Triple(B_TAIL3, -3.0, 80.0), Triple(B_FLUKE, -3.0, 96.0),
    )

    /** Rig + Vorwärtskinematik + Boxen für eine Lage. */
    fun solve(s: State): Frame {
        val r = LeviathanRig.shown(s.pose, s.poseAge, s.previous, s.previousAge, s.age, s.phase)
        return solve(s, r)
    }

    fun solve(s: State, r: FloatArray): Frame {
        val f = Frame(s)
        val mo = s.motion
        val R = LeviathanRig
        val ph = mo.swimPhase
        val w = r[R.WAVE]
        val up = max(0f, mo.climb)
        val down = max(0f, -mo.climb) * r[R.GLIDE]
        val drive = w * (1f + 0.45f * up - 0.6f * down)
        val flex = 0.4f + 0.6f * w.coerceIn(0f, 1f)
        val bank = mo.bank * flex
        val leadYaw = mo.leadYaw * flex
        val leadPitch = mo.leadPitch * flex

        // je Knochen: Drehung (pitch, yaw, roll), Skalierung, Versatz des Drehpunkts
        val rot = Array(BONE_COUNT) { FloatArray(3) }
        val scale = Array(BONE_COUNT) { floatArrayOf(1f, 1f, 1f) }
        val shift = Array(BONE_COUNT) { FloatArray(3) }
        val pitchRad = if (isBeachedPose(s.pose)) 0f else s.pitch * RAD
        rot[B_ROOT][0] = pitchRad + r[R.ROOT_PITCH] + drive * 0.022f * sin(ph + 0.6f)
        val squash = r[R.SQUASH]
        scale[B_ROOT][0] = r[R.SCALE] * (1f + 0.3f * squash); scale[B_ROOT][1] = r[R.SCALE] * (1f - squash); scale[B_ROOT][2] = r[R.SCALE]
        rot[B_BODY][1] = r[R.ROOT_YAW]
        rot[B_BODY][2] = r[R.ROOT_ROLL] - bank
        shift[B_BODY][0] = r[R.ROOT_X]; shift[B_BODY][1] = r[R.ROOT_Y] + drive * sin(ph + 1.9f); shift[B_BODY][2] = r[R.ROOT_Z]
        rot[B_NECK][0] = r[R.NECK] - drive * 0.02f * sin(ph + 0.9f) + leadPitch * 0.45f
        rot[B_NECK][1] = r[R.HEAD_YAW] * 0.45f + leadYaw * 0.45f
        rot[B_HEAD][0] = r[R.HEAD] - drive * 0.025f * sin(ph + 1.3f) + leadPitch * 0.55f
        rot[B_HEAD][1] = r[R.HEAD_YAW] * 0.55f + leadYaw * 0.55f
        rot[B_HEAD][2] = r[R.HEAD_ROLL] + bank * 0.3f
        rot[B_JAW][0] = max(0f, r[R.JAW] + 0.02f)
        for (i in 0 until 6) {
            val b = SPINE[i]
            rot[b][0] = r[R.S1 + i] + drive * R.WAVE_AMP[i] * sin(ph - 0.5f - i * 0.55f) + mo.bendPitch[i] * flex
            rot[b][1] = r[R.Y1 + i] + mo.bendYaw[i] * flex
            rot[b][2] = r[R.TWIST] * R.TWIST_SHARE[i]
            val g = max(0.02f, r[R.G1 + i]) * (if (i == 1) r[R.TAILSCALE] else 1f)
            scale[b][0] = g; scale[b][1] = g; scale[b][2] = g
        }
        rot[B_FLUKE][2] += r[R.FLUKE_ROLL]
        val lobe = r[R.LOBE] + drive * 0.13f * cos(ph - 3.2f)
        val lobeTip = r[R.LOBE] * 0.8f + drive * 0.12f * cos(ph - 3.8f)
        rot[B_FLUKE_L][2] = lobe; rot[B_FLUKE_R][2] = -lobe
        rot[B_FLUKE_L2][2] = lobeTip; rot[B_FLUKE_R2][2] = -lobeTip
        // Brustflossen (Rudern wie im Modell)
        val fw = r[R.FINWAVE]
        val trim = fw.coerceIn(0f, 1f)
        val stroke = fw * (1f + 0.5f * up - 0.55f * down)
        val row = stroke * 0.13f * sin(ph * 0.5f - 0.8f)
        val rowElbow = stroke * 0.16f * sin(ph * 0.5f - 1.6f)
        val rowTip = stroke * 0.18f * sin(ph * 0.5f - 2.3f)
        val twist = stroke * 0.1f * cos(ph * 0.5f - 0.8f) + trim * (0.1f * down - 0.2f * up)
        val sweep = trim * (0.06f * up - 0.24f * down)
        val lift = trim * 0.13f * down + mo.finLag
        rot[B_FIN_L][1] = -(r[R.FL_SWEEP] + sweep); rot[B_FIN_L][2] = -(r[R.FL_FLAP] + row + lift); rot[B_FIN_L][0] = r[R.FL_TWIST] + twist
        rot[B_FIN_L2][2] = -(r[R.FL_ELBOW] + rowElbow + mo.finLag * 0.8f)
        rot[B_FIN_L3][2] = -(r[R.FL_ELBOW] * 0.7f + rowTip + mo.finLag * 0.6f)
        rot[B_FIN_R][1] = r[R.FR_SWEEP] + sweep; rot[B_FIN_R][2] = r[R.FR_FLAP] + row * 0.9f + lift; rot[B_FIN_R][0] = r[R.FR_TWIST] + twist
        rot[B_FIN_R2][2] = r[R.FR_ELBOW] + rowElbow + mo.finLag * 0.8f
        rot[B_FIN_R3][2] = r[R.FR_ELBOW] * 0.7f + rowTip + mo.finLag * 0.6f
        val hs = r[R.HEART].coerceIn(0.3f, 2f)
        scale[B_HEART][0] = hs; scale[B_HEART][1] = hs; scale[B_HEART][2] = hs

        // Modellraum (px) → Welt: Blick nach Yaw, Spiegelung (−x, −y), 0,3 Blöcke je px, Ursprung BODY_UP über pos
        val yaw = Math.toRadians(s.yaw.toDouble())
        val sy = sin(yaw); val cy = cos(yaw)
        // Spalten: Welt-Richtung der Modellachsen +X (= links), +Y (= unten), +Z (= hinten)
        val world = doubleArrayOf(
            cy * PX, 0.0, sy * PX,
            0.0, -PX, 0.0,
            sy * PX, 0.0, -cy * PX,
        )
        // world als Matrix mit Spalten: Zeile i = Weltachse i
        val W = doubleArrayOf(world[0], world[3], world[6], world[1], world[4], world[7], world[2], world[5], world[8])
        val origin = Vec3d(s.pos.x, s.pos.y + BODY_UP, s.pos.z)

        val local = DoubleArray(12)
        for (i in 0 until BONE_COUNT) {
            val b = BONES[i]
            val pb = if (b.parent >= 0) BONES[b.parent] else null
            // lokal: T(Drehpunkt relativ zum Eltern-Drehpunkt + Versatz) · Rz·Ry·Rx · S
            val tx = b.x - (pb?.x ?: 0.0) + shift[i][0]
            val ty = b.y - (pb?.y ?: 0.0) + shift[i][1]
            val tz = b.z - (pb?.z ?: 0.0) + shift[i][2]
            rotationZYX(rot[i][2].toDouble(), rot[i][1].toDouble(), rot[i][0].toDouble(), local)
            for (row in 0 until 3) {
                local[row * 3] *= scale[i][0]; local[row * 3 + 1] *= scale[i][1]; local[row * 3 + 2] *= scale[i][2]
            }
            local[9] = tx; local[10] = ty; local[11] = tz
            val out = f.m[i]
            if (pb == null) {
                // Welt = W · local, Ursprung = origin + W · t
                mul3(W, local, out)
                out[9] = origin.x + W[0] * tx + W[1] * ty + W[2] * tz
                out[10] = origin.y + W[3] * tx + W[4] * ty + W[5] * tz
                out[11] = origin.z + W[6] * tx + W[7] * ty + W[8] * tz
            } else {
                val p = f.m[b.parent]
                mul3(p, local, out)
                out[9] = p[9] + p[0] * tx + p[1] * ty + p[2] * tz
                out[10] = p[10] + p[3] * tx + p[4] * ty + p[5] * tz
                out[11] = p[11] + p[6] * tx + p[7] * ty + p[8] * tz
            }
        }

        // Boxen
        if (s.pose != WhalePose.HIDDEN) {
            for (i in 0 until COUNT) {
                val v = VOLUMES[i]
                val c = f.point(v.bone, v.cx, v.cy, v.cz)
                f.centers[i] = c
                val a = f.m[v.bone]
                val h = DoubleArray(3)
                for (ax in 0 until 3) {
                    val lx = a[ax * 3] * v.hx
                    val ly = a[ax * 3 + 1] * v.hy
                    val lz = a[ax * 3 + 2] * v.hz
                    h[ax] = if (v.round) abs(lz) + sqrt(lx * lx + ly * ly) else abs(lx) + abs(ly) + abs(lz)
                }
                f.boxes[i] = Box(c.x - h[0], c.y - h[1], c.z - h[2], c.x + h[0], c.y + h[1], c.z + h[2])
            }
        }
        return f
    }

    /** JOML `rotationZYX(z, y, x)` = Rz · Ry · Rx als 3×3 (Zeilen) in [out][0..8]. */
    private fun rotationZYX(z: Double, y: Double, x: Double, out: DoubleArray) {
        val cz = cos(z); val sz = sin(z); val cy = cos(y); val sy = sin(y); val cx = cos(x); val sx = sin(x)
        // Ry·Rx
        val a00 = cy; val a01 = sy * sx; val a02 = sy * cx
        val a10 = 0.0; val a11 = cx; val a12 = -sx
        val a20 = -sy; val a21 = cy * sx; val a22 = cy * cx
        out[0] = cz * a00 - sz * a10; out[1] = cz * a01 - sz * a11; out[2] = cz * a02 - sz * a12
        out[3] = sz * a00 + cz * a10; out[4] = sz * a01 + cz * a11; out[5] = sz * a02 + cz * a12
        out[6] = a20; out[7] = a21; out[8] = a22
    }

    /** out[0..8] = a[0..8] · b[0..8] (3×3, Zeilen). */
    private fun mul3(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        }
        r.copyInto(out, 0, 0, 9)
    }

    /** Inverse des 3×3-Teils (null, wenn entartet). */
    internal fun invert(a: DoubleArray): DoubleArray? {
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return null
        val d = 1.0 / det
        return doubleArrayOf(
            (a[4] * a[8] - a[5] * a[7]) * d, (a[2] * a[7] - a[1] * a[8]) * d, (a[1] * a[5] - a[2] * a[4]) * d,
            (a[5] * a[6] - a[3] * a[8]) * d, (a[0] * a[8] - a[2] * a[6]) * d, (a[2] * a[3] - a[0] * a[5]) * d,
            (a[3] * a[7] - a[4] * a[6]) * d, (a[1] * a[6] - a[0] * a[7]) * d, (a[0] * a[4] - a[1] * a[3]) * d,
        )
    }

    /**
     * Lage [p] relativ zum Volumen [i] im Frame [from] auf denselben Ort im Frame [to] übertragen (Mitnehmen der Reiter):
     * Drehung, Biegung, Rolle und Bewegung des Körpers wirken genau wie auf die Haut darunter.
     */
    fun carry(p: Vec3d, i: Int, from: Frame, to: Frame): Vec3d? {
        val v = VOLUMES[i]
        val ca = from.centers[i] ?: return null
        val cb = to.centers[i] ?: return null
        val inv = invert(from.m[v.bone]) ?: return null
        val b = to.m[v.bone]
        val dx = p.x - ca.x; val dy = p.y - ca.y; val dz = p.z - ca.z
        val lx = inv[0] * dx + inv[1] * dy + inv[2] * dz
        val ly = inv[3] * dx + inv[4] * dy + inv[5] * dz
        val lz = inv[6] * dx + inv[7] * dy + inv[8] * dz
        return Vec3d(cb.x + b[0] * lx + b[1] * ly + b[2] * lz, cb.y + b[3] * lx + b[4] * ly + b[5] * lz, cb.z + b[6] * lx + b[7] * ly + b[8] * lz)
    }

    /** Drehung (Grad, waagerecht) des Volumens [i] zwischen zwei Frames – der Reiter dreht den Blick mit. */
    fun turn(i: Int, from: Frame, to: Frame): Float {
        val bone = VOLUMES[i].bone
        val a = from.direction(bone, 0.0, 0.0, -1.0)
        val b = to.direction(bone, 0.0, 0.0, -1.0)
        if (a.horizontalLengthSquared() < 0.05 || b.horizontalLengthSquared() < 0.05) return 0f
        val ya = Math.toDegrees(atan2(-a.x, a.z)).toFloat()
        val yb = Math.toDegrees(atan2(-b.x, b.z)).toFloat()
        return MathHelper.wrapDegrees(yb - ya)
    }

    // ------------------------------------------------------------------------------------------------ alte Helfer

    fun forward(yaw: Float, pitch: Float): Vec3d {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        return Vec3d(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    fun right(yaw: Float): Vec3d {
        val y = Math.toRadians(yaw.toDouble())
        return Vec3d(-cos(y), 0.0, -sin(y))
    }

    /** Oben senkrecht zur Längsachse. */
    fun up(yaw: Float, pitch: Float): Vec3d = right(yaw).crossProduct(forward(yaw, pitch))

    /** Punkt am geraden Körper: [f] vorwärts, [s] rechts, [u] oben (relativ zur Körpermitte). */
    fun point(center: Vec3d, yaw: Float, pitch: Float, f: Double, s: Double, u: Double): Vec3d =
        center.add(forward(yaw, pitch).multiply(f)).add(right(yaw).multiply(s)).add(up(yaw, pitch).multiply(u))
}
