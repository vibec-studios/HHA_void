package dev.henny.hha_void.client.fx

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.option.Perspective
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Nummern der Cutscenes im FX-Paket. */
object VoidScene {
    const val INTRO = 0
    const val PHASE_2 = 1
    const val PHASE_3 = 2
    const val COLLAPSE = 3
    const val REBIRTH = 4
    const val TRUE_DEATH = 5
    const val DEVOUR = 6
    const val BELLY = 7
    const val BREAKOUT = 8
}

/**
 * Filmkamera für den Bosskampf. Der Server startet eine Szene und hält derweil den Kampf an;
 * der Client führt die Kamera, friert die Eingaben ein und blendet Balken, Text und Titel ein.
 */
object VoidCutscene {

    /** Punkt einer Kamerafahrt. */
    private sealed interface Key {
        /** Relativ zum Anker: [f] vor dem Boss, [r] seitlich, [u] nach oben. */
        data class At(val f: Double, val r: Double, val u: Double) : Key

        /** Der Boss selbst, [up] über seinen Füßen; für Fahrten, die ihm folgen. */
        data class Boss(val up: Double) : Key

        /** Relativ zum Boss und seiner Blickrichtung: [f] vor ihm, [r] rechts von ihm, [u] über seiner Position. */
        data class BossRel(val f: Double, val r: Double, val u: Double) : Key

        /** Augen des Spielers, oder der Punkt, auf den er blickt. */
        data object Eye : Key
        data object EyeLook : Key

        /** Wo die vorige Einstellung aufgehört hat. */
        data object Last : Key
    }

    private class Shot(
        val start: Int, val end: Int,
        val camFrom: Key, val camTo: Key,
        val lookFrom: Key, val lookTo: Key,
        val fovFrom: Float = 1f, val fovTo: Float = 1f,
        val arc: Boolean = false,
    )

    private class Caption(val start: Int, val end: Int, val key: String)

    private class Card(val start: Int, val end: Int, val title: String, val subtitle: String, val color: Formatting)

    /** Kamera-Erschütterung zwischen [start] und [end]. */
    private class Shake(val start: Int, val end: Int, val strength: Float)

    /** Schwarzblende: dunkelt von [start] bis [full] ab, bleibt bis [hold], hellt bis [end] auf (-1 = von Anfang an schwarz). */
    private class Fade(val start: Int, val full: Int, val hold: Int, val end: Int)

    private class Scene(
        val length: Int,
        val shots: List<Shot>,
        val captions: List<Caption> = emptyList(),
        val cards: List<Card> = emptyList(),
        val flashes: List<Int> = emptyList(),
        val shakes: List<Shake> = emptyList(),
        val fades: List<Fade> = emptyList(),
    )

    private const val RETURN_TICKS = 15

    private fun line(key: String) = "hha_void.cut.$key"
    private fun title(key: String) = "hha_void.boss.title.$key"

    /*
     * Die Szenen folgen der Choreografie in VoidBossEntity (tickIntro / tickScene / updatePostDeath).
     * Anker = Mitte des Arenabodens (Bauch-Szene: Mitte des Magens); "vorne" = Blickrichtung, die der Server mitschickt.
     * Maße für den Leviathan (≈ 58 Blöcke lang, Körpermitte 7 Blöcke über seiner Position).
     */
    private fun scene(id: Int): Scene = when (id) {
        VoidScene.INTRO -> Scene(
            220,
            listOf(
                // Vom Boden der Plattform: Der Himmel über der Tiefe reißt auf
                Shot(0, 34, Key.At(-12.0, -4.0, 2.4), Key.At(-9.0, -3.0, 3.0), Key.At(88.0, 0.0, 42.0), Key.At(88.0, 0.0, 44.0), 1.1f, 1f),
                // Er schält sich aus dem Riss und gleitet heran – riesig
                Shot(34, 94, Key.At(-2.0, 18.0, 8.0), Key.At(30.0, 26.0, 18.0), Key.Boss(7.0), Key.Boss(7.0), 0.95f, 0.85f),
                // Halbe Runde um die Plattform, die Kamera kreist mit
                Shot(94, 168, Key.At(-30.0, -45.0, 12.0), Key.At(30.0, -48.0, 20.0), Key.Boss(7.0), Key.Boss(7.0), 1.05f, 1.05f, arc = true),
                // Das Brüllen, vom Boden aus gesehen
                Shot(168, 205, Key.At(-25.0, 8.0, 2.6), Key.At(-20.0, 12.0, 2.4), Key.Boss(8.0), Key.Boss(12.0), 0.9f, 0.8f),
                Shot(205, 220, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 0.8f, 1f),
            ),
            listOf(
                Caption(6, 34, line("intro0")),
                Caption(44, 92, line("intro1")),
                Caption(170, 218, line("intro2")),
            ),
            listOf(Card(176, 214, title("phase1"), title("phase1.sub"), Formatting.LIGHT_PURPLE)),
            listOf(34),
            shakes = listOf(Shake(168, 200, 0.6f)),
        )
        // „Die Tiefe erwacht“: Brüllen, Abtauchen unter die Arena, der Boden reißt, Inseln stürzen, Durchbruch
        VoidScene.PHASE_2 -> Scene(
            200,
            listOf(
                // Brüllen, schräg von vorn – die Kamera hängt am Wal
                Shot(0, 20, Key.BossRel(52.0, -26.0, 12.0), Key.BossRel(44.0, -22.0, 10.0), Key.Boss(8.0), Key.Boss(9.0), 1f, 0.9f),
                // er stürzt sich über die Kante in die Tiefe (vorne = Richtung Mitte → Wal)
                Shot(20, 60, Key.At(60.0, 22.0, 20.0), Key.At(64.0, 26.0, 16.0), Key.Boss(4.0), Key.Boss(0.0), 1f, 1.05f),
                // Stille. Dann bricht der Boden auf – Blick über die Arena auf die Stelle am Rand gegenüber
                Shot(60, 100, Key.At(6.0, 0.0, 20.0), Key.At(2.0, 0.0, 17.0), Key.At(-58.0, 0.0, 0.0), Key.At(-58.0, 0.0, 6.0), 1f, 0.92f),
                // Durchbruch: von unten gesehen, er schießt in den Himmel
                Shot(100, 150, Key.At(-6.0, 12.0, 3.5), Key.At(-10.0, 10.0, 3.5), Key.At(-58.0, 0.0, 8.0), Key.Boss(28.0), 1.1f, 0.85f),
                Shot(150, 185, Key.At(-90.0, 40.0, 40.0), Key.At(-50.0, 70.0, 50.0), Key.Boss(7.0), Key.Boss(7.0), 0.9f, 1f, arc = true),
                Shot(185, 200, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 1f, 1f),
            ),
            listOf(Caption(4, 60, line("phase2")), Caption(104, 150, line("phase2b"))),
            listOf(Card(120, 182, title("phase2"), title("phase2.sub"), Formatting.LIGHT_PURPLE)),
            listOf(104),
            shakes = listOf(Shake(0, 18, 0.5f), Shake(64, 100, 0.35f), Shake(100, 120, 1.2f)),
        )
        // Raserei ohne Bauch-Phase (Rückfall, z. B. wenn niemand verschlungen werden konnte)
        VoidScene.PHASE_3 -> Scene(
            110,
            listOf(
                Shot(0, 45, Key.At(36.0, -14.0, 22.0), Key.At(28.0, -10.0, 26.0), Key.Boss(7.0), Key.Boss(7.0), 1f, 0.9f),
                Shot(45, 92, Key.At(24.0, 10.0, 30.0), Key.At(36.0, 14.0, 26.0), Key.Boss(7.0), Key.Boss(7.0), 0.85f, 1.1f),
                Shot(92, 110, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 1.1f, 1f),
            ),
            listOf(Caption(4, 44, line("phase3"))),
            listOf(Card(48, 100, title("phase3"), title("phase3.sub"), Formatting.GOLD)),
            listOf(46),
            shakes = listOf(Shake(40, 60, 0.9f)),
        )
        // Verschlingen: er stellt sich am Rand auf, das Maul reißt auf, alles wird hineingezogen – Schwarz
        VoidScene.DEVOUR -> Scene(
            180,
            listOf(
                Shot(0, 40, Key.At(-30.0, -25.0, 4.0), Key.At(-26.0, -20.0, 5.0), Key.Boss(7.0), Key.Boss(7.0), 1f, 0.95f),
                Shot(40, 100, Key.At(-8.0, 6.0, 2.0), Key.At(-10.0, 3.0, 3.0), Key.Boss(6.0), Key.Boss(6.0), 0.95f, 0.85f),
                // die Kamera wird selbst ins Maul gezogen
                Shot(100, 145, Key.At(20.0, 0.0, 3.0), Key.At(52.0, 0.0, 8.0), Key.Boss(5.0), Key.Boss(5.0), 0.85f, 0.65f),
                Shot(145, 180, Key.Last, Key.Last, Key.Last, Key.Last, 0.65f, 0.65f),
            ),
            listOf(Caption(4, 40, line("devour0")), Caption(48, 120, line("devour1"))),
            emptyList(),
            emptyList(),
            shakes = listOf(Shake(40, 140, 0.45f)),
            fades = listOf(Fade(126, 140, 180, 181)),
        )
        // Im Bauch: aus dem Schwarz heraus, Fahrt durch den Magen zu den Kernen
        VoidScene.BELLY -> Scene(
            100,
            listOf(
                Shot(0, 40, Key.At(-26.0, 0.0, 0.0), Key.At(-18.0, 4.0, 3.0), Key.At(12.0, 0.0, -3.0), Key.At(14.0, 0.0, -2.0), 1.05f, 1f),
                Shot(40, 80, Key.At(-4.0, -14.0, 2.0), Key.At(6.0, -13.0, 4.0), Key.At(17.0, 7.0, -1.0), Key.At(-17.0, -8.0, -1.0), 1f, 1f),
                Shot(80, 100, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 1f, 1f),
            ),
            listOf(Caption(10, 58, line("belly0")), Caption(64, 98, line("belly1"))),
            listOf(Card(18, 70, title("belly"), title("belly.sub"), Formatting.RED)),
            emptyList(),
            fades = listOf(Fade(-1, 0, 6, 30)),
        )
        // Ausbruch: die Flanke reißt auf, die Spieler fliegen ins Freie, er bricht zusammen – Raserei
        VoidScene.BREAKOUT -> Scene(
            150,
            listOf(
                Shot(0, 22, Key.BossRel(10.0, 40.0, 16.0), Key.BossRel(6.0, 34.0, 18.0), Key.Boss(7.0), Key.Boss(7.0), 1f, 0.9f),
                Shot(22, 60, Key.BossRel(2.0, 30.0, 12.0), Key.BossRel(-6.0, 48.0, 20.0), Key.Boss(6.0), Key.Boss(4.0), 0.9f, 1.1f),
                Shot(60, 130, Key.At(30.0, -48.0, 10.0), Key.At(-30.0, -48.0, 14.0), Key.Boss(5.0), Key.Boss(5.0), 1.05f, 1f, arc = true),
                Shot(130, 150, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 1f, 1f),
            ),
            listOf(Caption(4, 22, line("breakout0")), Caption(64, 128, line("phase3"))),
            listOf(Card(70, 130, title("phase3"), title("phase3.sub"), Formatting.GOLD)),
            listOf(22),
            shakes = listOf(Shake(0, 22, 0.4f), Shake(22, 40, 1.3f), Shake(56, 64, 0.8f)),
            fades = listOf(Fade(-1, 0, 0, 12)),
        )
        else -> Scene(
            260,
            listOf(
                // Das letzte Brüllen
                Shot(0, 60, Key.At(-40.0, -20.0, 12.0), Key.At(-32.0, -16.0, 16.0), Key.Boss(7.0), Key.Boss(7.0), 1f, 0.9f),
                // Er bäumt sich auf, rollt auf den Rücken und zerreißt in Licht
                Shot(60, 150, Key.At(-45.0, 20.0, 14.0), Key.At(35.0, 40.0, 24.0), Key.Boss(7.0), Key.Boss(7.0), 0.9f, 1f, arc = true),
                // Blick hinauf: ein neues Sternbild
                Shot(150, 230, Key.At(-45.0, 0.0, 6.0), Key.At(-50.0, 0.0, 4.0), Key.At(0.0, 0.0, 45.0), Key.At(0.0, 0.0, 64.0), 1.1f, 1.25f),
                Shot(230, 260, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 1.25f, 1f),
            ),
            listOf(
                Caption(8, 58, line("death0")),
                Caption(150, 228, line("death1")),
            ),
            listOf(Card(160, 215, title("end"), title("end.sub"), Formatting.LIGHT_PURPLE)),
            listOf(150),
            shakes = listOf(Shake(0, 40, 0.5f), Shake(90, 150, 0.3f), Shake(150, 160, 1f)),
        )
    }

    private var active: Scene? = null
    private var clock = 0
    private var anchor = Vec3d.ZERO
    private var forward = Vec3d(0.0, 0.0, 1.0)
    private var right = Vec3d(1.0, 0.0, 0.0)
    private var bossId = 0

    /** Den Boss in dieser Szene schon gesehen? Fehlt er danach länger, wurde er entfernt → zurück zum Spieler. */
    private var bossSeen = false
    private var bossMissing = 0
    private var lastBossPos: Vec3d? = null
    private var lastBossYaw = 0f
    private var savedPerspective: Perspective? = null
    private var skipHold = 0
    private var skipped = false

    private var currentShot: Shot? = null
    private var lastCam = Vec3d.ZERO
    private var lastLook = Vec3d.ZERO
    private var snapCam = Vec3d.ZERO
    private var snapLook = Vec3d.ZERO
    private var hasFrame = false

    private var outX = 0.0
    private var outY = 0.0
    private var outZ = 0.0
    private var outYaw = 0f
    private var outPitch = 0f
    private var outFov = 1f

    @JvmStatic
    fun isActive() = active != null

    @JvmStatic fun camX() = outX
    @JvmStatic fun camY() = outY
    @JvmStatic fun camZ() = outZ
    @JvmStatic fun camYaw() = outYaw
    @JvmStatic fun camPitch() = outPitch
    @JvmStatic fun fovFactor() = outFov

    /** [yaw] ist die Blickrichtung des Bosses, wenn die Szene beginnt. */
    fun start(id: Int, boss: Int, at: Vec3d, yaw: Float) {
        val client = MinecraftClient.getInstance()
        val rad = Math.toRadians(yaw.toDouble())
        anchor = at
        forward = Vec3d(-sin(rad), 0.0, cos(rad))
        right = Vec3d(-cos(rad), 0.0, -sin(rad))
        bossId = boss
        bossSeen = false
        bossMissing = 0
        lastBossPos = null
        active = scene(id)
        clock = 0
        skipHold = 0
        skipped = false
        currentShot = null
        hasFrame = false
        if (savedPerspective == null) {
            savedPerspective = client.options.perspective
            client.options.perspective = Perspective.THIRD_PERSON_BACK
        }
    }

    fun stop() {
        active = null
        val saved = savedPerspective ?: return
        savedPerspective = null
        val client = MinecraftClient.getInstance()
        client.options.perspective = saved
        // Was während der Szene gedrückt wurde, soll danach nichts auslösen
        KeyBinding.unpressAll()
    }

    fun tick(client: MinecraftClient) {
        val scene = active ?: return
        clock++
        if (clock >= scene.length) {
            stop()
            return
        }
        // Boss weg (zum Beispiel per Befehl entfernt): direkt zurück zum Spieler
        for (sh in scene.shakes) if (clock in sh.start..sh.end) VoidFxClient.shake(anchor, sh.strength, 1000.0)
        // Fehlt der Boss nur, weil er (noch) zu weit weg ist, läuft die Szene mit dem Anker weiter
        val present = client.world?.getEntityById(bossId) != null
        if (present) {
            bossSeen = true
            bossMissing = 0
        } else {
            bossMissing++
        }
        val gone = bossSeen && bossMissing > 20
        if (!skipped && (gone || holdingSkip(client))) skip()
    }

    private fun holdingSkip(client: MinecraftClient): Boolean {
        if (client.options.sneakKey.isPressed) skipHold++ else skipHold = 0
        return skipHold >= SKIP_TICKS
    }

    private fun skip() {
        skipped = true
        active = Scene(
            clock + RETURN_TICKS,
            listOf(Shot(clock, clock + RETURN_TICKS, Key.Last, Key.Eye, Key.Last, Key.EyeLook, outFov, 1f)),
        )
    }

    private fun resolve(key: Key, tickProgress: Float, snapshot: Vec3d): Vec3d {
        val client = MinecraftClient.getInstance()
        val player = client.player
        return when (key) {
            is Key.At -> anchor.add(forward.multiply(key.f)).add(right.multiply(key.r)).add(0.0, key.u, 0.0)
            is Key.Boss -> {
                val boss = client.world?.getEntityById(bossId)
                if (boss != null) {
                    lastBossPos = boss.getLerpedPos(tickProgress)
                    lastBossYaw = boss.getYaw(tickProgress)
                }
                (lastBossPos ?: anchor).add(0.0, key.up, 0.0)
            }
            is Key.BossRel -> {
                val boss = client.world?.getEntityById(bossId)
                if (boss != null) {
                    lastBossPos = boss.getLerpedPos(tickProgress)
                    lastBossYaw = boss.getYaw(tickProgress)
                }
                val rad = Math.toRadians(lastBossYaw.toDouble())
                val f = Vec3d(-sin(rad), 0.0, cos(rad))
                val r = Vec3d(-cos(rad), 0.0, -sin(rad))
                (lastBossPos ?: anchor).add(f.multiply(key.f)).add(r.multiply(key.r)).add(0.0, key.u, 0.0)
            }
            Key.Eye -> player?.getCameraPosVec(tickProgress) ?: snapshot
            Key.EyeLook -> player?.let { it.getCameraPosVec(tickProgress).add(it.getRotationVec(tickProgress).multiply(16.0)) } ?: snapshot
            Key.Last -> snapshot
        }
    }

    private fun ease(t: Float): Float {
        val k = t.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /** Fahrt um den Anker: Radius und Winkel werden überblendet statt der Position. */
    private fun arcBetween(a: Vec3d, b: Vec3d, k: Double): Vec3d {
        val ax = a.x - anchor.x
        val az = a.z - anchor.z
        val bx = b.x - anchor.x
        val bz = b.z - anchor.z
        val fromAngle = atan2(az, ax)
        var delta = atan2(bz, bx) - fromAngle
        while (delta > Math.PI) delta -= Math.PI * 2.0
        while (delta < -Math.PI) delta += Math.PI * 2.0
        val radius = sqrt(ax * ax + az * az) + (sqrt(bx * bx + bz * bz) - sqrt(ax * ax + az * az)) * k
        val angle = fromAngle + delta * k
        return Vec3d(anchor.x + cos(angle) * radius, a.y + (b.y - a.y) * k, anchor.z + sin(angle) * radius)
    }

    /** Nie durch Säulen filmen: die Kamera rückt vor das erste Hindernis. */
    private fun clip(cam: Vec3d): Vec3d {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return cam
        val entity = client.player ?: return cam
        val from = anchor.add(0.0, 8.0, 0.0)
        val hit = world.raycast(RaycastContext(from, cam, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, entity))
        if (hit.type == HitResult.Type.MISS) return cam
        val back = from.subtract(hit.pos).normalize()
        return hit.pos.add(back.multiply(0.4))
    }

    /** Wird vom Kamera-Mixin pro Bild aufgerufen. */
    @JvmStatic
    fun update(tickProgress: Float) {
        val scene = active ?: return
        val t = clock + tickProgress
        val shot = scene.shots.lastOrNull { it.start <= t } ?: scene.shots.first()
        if (shot !== currentShot) {
            currentShot = shot
            val eye = MinecraftClient.getInstance().player?.getCameraPosVec(tickProgress) ?: anchor
            snapCam = if (hasFrame) lastCam else eye
            snapLook = if (hasFrame) lastLook else eye.add(forward)
        }
        val k = ease((t - shot.start) / (shot.end - shot.start).toFloat()).toDouble()
        val a = resolve(shot.camFrom, tickProgress, snapCam)
        val b = resolve(shot.camTo, tickProgress, snapCam)
        val moved = if (shot.arc) arcBetween(a, b, k) else a.lerp(b, k)
        // Nur für Fahrten über dem Anker: Augenposition und Rückweg gehören dem Spieler
        val cam = if (shot.camTo is Key.At) clip(moved) else moved
        val look = resolve(shot.lookFrom, tickProgress, snapLook).lerp(resolve(shot.lookTo, tickProgress, snapLook), k)

        lastCam = cam
        lastLook = look
        hasFrame = true
        val dir = look.subtract(cam)
        val length = dir.length().coerceAtLeast(0.001)
        outX = cam.x
        outY = cam.y
        outZ = cam.z
        outYaw = Math.toDegrees(atan2(-dir.x, dir.z)).toFloat()
        outPitch = Math.toDegrees(-asin((dir.y / length).coerceIn(-1.0, 1.0))).toFloat()
        outFov = shot.fovFrom + (shot.fovTo - shot.fovFrom) * k.toFloat()
    }

    // ---------- Einblendungen ----------

    /** Ersetzt während der Szene die normale Anzeige. */
    @JvmStatic
    fun renderHud(context: DrawContext, tickProgress: Float) {
        val scene = active ?: return
        val client = MinecraftClient.getInstance()
        val t = clock + tickProgress
        val w = context.scaledWindowWidth
        val h = context.scaledWindowHeight

        val inBars = ((t / 10f).coerceAtMost(1f)) * ((scene.length - t) / 10f).coerceIn(0f, 1f)
        val bar = (h * 0.12f * ease(inBars)).toInt()
        context.fill(0, 0, w, bar, 0xFF000000.toInt())
        context.fill(0, h - bar, w, h, 0xFF000000.toInt())

        for (at in scene.flashes) {
            val age = t - at
            if (age in 0f..6f) {
                val a = ((1f - age / 6f) * 0.42f * 255f).toInt()
                context.fill(0, 0, w, h, (a shl 24) or 0xF0C8FF)
            }
        }

        for (fade in scene.fades) {
            val k = when {
                t < fade.start -> 0f
                t < fade.full -> (t - fade.start) / (fade.full - fade.start).coerceAtLeast(1)
                t < fade.hold -> 1f
                t < fade.end -> 1f - (t - fade.hold) / (fade.end - fade.hold).coerceAtLeast(1)
                else -> 0f
            }
            if (k > 0f) context.fill(0, 0, w, h, ((k.coerceIn(0f, 1f) * 255f).toInt() shl 24))
        }

        val font = client.textRenderer
        for (caption in scene.captions) {
            if (t < caption.start || t > caption.end) continue
            val full = Text.translatable(caption.key).string
            // Schreibmaschine: Oruun singt langsam
            val shown = ((t - caption.start) * 1.6f).toInt().coerceIn(0, full.length)
            val fade = ((caption.end - t) / 5f).coerceIn(0f, 1f)
            val color = (((fade * 255f).toInt().coerceAtLeast(8)) shl 24) or 0xE6C8FF
            context.drawCenteredTextWithShadow(font, full.substring(0, shown), w / 2, h - bar / 2 - 4, color)
        }

        for (card in scene.cards) {
            if (t < card.start || t > card.end) continue
            val fade = ((t - card.start) / 8f).coerceAtMost(1f) * ((card.end - t) / 8f).coerceIn(0f, 1f)
            val alpha = ((fade * 255f).toInt().coerceAtLeast(8)) shl 24
            // Der Titel zieht sich langsam zusammen
            val grow = 3.3f - ease((t - card.start) / (card.end - card.start).toFloat()) * 0.3f
            val matrices = context.matrices
            matrices.pushMatrix()
            matrices.translate(w / 2f, h * 0.36f)
            matrices.scale(grow, grow)
            val heading = Text.translatable(card.title).formatted(card.color, Formatting.BOLD)
            context.drawCenteredTextWithShadow(font, heading, 0, 0, alpha or 0xFFFFFF)
            matrices.popMatrix()
            context.drawCenteredTextWithShadow(font, Text.translatable(card.subtitle), w / 2, (h * 0.36f).toInt() + 34, alpha or 0xE6C8FF)
        }

        if (!skipped && t > 20f) {
            val hint = Text.translatable("hha_void.cut.skip")
            val x = w - font.getWidth(hint) - 8
            context.drawTextWithShadow(font, hint, x, h - bar / 2 - 4, 0xFF8A7A9A.toInt())
            if (skipHold > 0) {
                val length = (font.getWidth(hint) * skipHold / SKIP_TICKS.toFloat()).toInt()
                context.fill(x, h - bar / 2 + 6, x + length, h - bar / 2 + 7, 0xFFE6C8FF.toInt())
            }
        }
    }

    private const val SKIP_TICKS = 15
}
