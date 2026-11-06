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

    private class Scene(
        val length: Int,
        val shots: List<Shot>,
        val captions: List<Caption> = emptyList(),
        val cards: List<Card> = emptyList(),
        val flashes: List<Int> = emptyList(),
    )

    private const val RETURN_TICKS = 15

    private fun line(key: String) = "hha_void.cut.$key"
    private fun title(key: String) = "hha_void.boss.title.$key"

    private fun scene(id: Int): Scene = when (id) {
        VoidScene.INTRO -> Scene(
            190,
            listOf(
                // Der Riss öffnet sich hoch über der leeren Arena
                Shot(0, 60, Key.At(15.0, -6.0, 1.6), Key.At(13.0, -3.0, 1.2), Key.At(0.0, 0.0, 26.0), Key.At(0.0, 0.0, 33.0), 1.1f, 1f),
                // Er stürzt heraus: die Kamera folgt ihm von unten
                Shot(60, 77, Key.At(11.0, 6.0, 1.0), Key.At(11.0, 6.0, 1.0), Key.Boss(3.0), Key.Boss(3.0), 0.9f, 1.1f),
                // Einschlag, tief am Boden
                Shot(77, 132, Key.At(10.0, -3.0, 0.8), Key.At(8.0, -1.0, 1.6), Key.At(0.0, 0.0, 2.4), Key.At(0.6, 0.0, 5.2), 1.15f, 0.9f),
                // Er richtet sich auf und brüllt, die Kamera weicht zurück
                Shot(132, 175, Key.At(8.0, -1.0, 1.8), Key.At(16.0, -5.0, 3.0), Key.At(0.5, 0.0, 6.0), Key.At(0.0, 0.0, 4.0), 0.85f, 1.05f),
                Shot(175, 190, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 1.05f, 1f),
            ),
            listOf(
                Caption(8, 56, line("intro0")),
                Caption(84, 110, line("intro1")),
                Caption(110, 132, line("intro2")),
            ),
            listOf(Card(138, 176, title("phase1"), title("phase1.sub"), Formatting.LIGHT_PURPLE)),
            listOf(77),
        )
        VoidScene.PHASE_2, VoidScene.PHASE_3 -> {
            val key = if (id == VoidScene.PHASE_2) "phase2" else "phase3"
            Scene(
                64,
                listOf(
                    Shot(0, 50, Key.At(13.0, -5.0, 1.2), Key.At(9.5, -2.0, 1.8), Key.At(0.0, 0.0, 4.5), Key.At(0.5, 0.0, 5.0), 1.05f, 0.9f),
                    Shot(50, 64, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 0.88f, 1f),
                ),
                cards = listOf(Card(6, 52, title(key), title("$key.sub"), Formatting.LIGHT_PURPLE)),
                flashes = listOf(0),
            )
        }
        VoidScene.COLLAPSE -> Scene(
            78,
            listOf(
                Shot(0, 34, Key.At(12.0, -4.0, 1.4), Key.At(11.0, -3.0, 2.6), Key.At(0.0, 0.0, 4.2), Key.At(0.0, 0.0, 1.6), 0.95f, 0.88f),
                Shot(34, 66, Key.At(10.0, 3.0, 6.0), Key.At(7.0, 8.0, 5.0), Key.At(0.0, 0.0, 0.6), Key.At(0.0, 0.0, 0.6), 1f, 1f, arc = true),
                Shot(66, 78, Key.Last, Key.Eye, Key.Last, Key.EyeLook),
            ),
            listOf(Caption(4, 32, line("collapse"))),
            listOf(Card(36, 70, title("paid"), title("paid.sub"), Formatting.GOLD)),
        )
        VoidScene.REBIRTH -> Scene(
            265,
            listOf(
                // Aus dem Blick des Spielers: Die Trümmer ziehen die Kamera an
                Shot(0, 18, Key.Eye, Key.Eye, Key.EyeLook, Key.At(0.0, 0.0, 1.0), 1f, 0.55f),
                Shot(18, 42, Key.At(9.0, -2.5, 0.7), Key.At(7.5, -1.5, 1.0), Key.At(0.5, 0.0, 1.0), Key.At(0.5, 0.0, 1.2), 0.9f, 0.8f),
                // Der Himmel reißt auf
                Shot(42, 95, Key.At(17.0, -7.0, 1.2), Key.At(14.0, -2.0, 0.7), Key.At(0.0, 0.0, 16.0), Key.At(0.0, 0.0, 70.0), 1.15f, 1.25f),
                // Die Strahlen setzen ihn wieder zusammen
                Shot(95, 165, Key.At(8.0, -5.5, 3.0), Key.At(5.5, 7.0, 3.6), Key.At(0.0, 0.0, 1.6), Key.At(0.0, 0.0, 4.2), 1.05f, 1f, arc = true),
                // Ganz nah am Gesicht
                Shot(165, 205, Key.At(6.0, -0.8, 4.2), Key.At(4.6, -0.2, 5.0), Key.At(0.8, 0.0, 5.6), Key.At(0.8, 0.0, 5.7), 0.8f, 0.7f),
                // Das Brüllen schleudert die Kamera zurück
                Shot(205, 250, Key.At(6.0, 0.0, 4.4), Key.At(17.0, -5.0, 3.0), Key.At(0.6, 0.0, 5.6), Key.At(0.0, 0.0, 5.0), 0.8f, 1.1f),
                Shot(250, 265, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 1.1f, 1f),
            ),
            listOf(
                Caption(22, 42, line("rebirth0")),
                Caption(52, 92, line("rebirth1")),
                Caption(168, 204, line("rebirth2")),
            ),
            listOf(Card(210, 252, title("phase4"), title("phase4.sub"), Formatting.DARK_RED)),
            listOf(18, 42, 205),
        )
        else -> Scene(
            130,
            listOf(
                Shot(0, 46, Key.At(11.0, -3.5, 1.8), Key.At(8.5, -1.5, 3.0), Key.At(0.4, 0.0, 4.0), Key.At(0.6, 0.0, 3.8), 0.95f, 0.85f),
                Shot(46, 115, Key.At(9.0, 3.0, 3.0), Key.At(13.0, 5.0, 12.0), Key.At(0.0, 0.0, 2.0), Key.At(0.0, 0.0, 9.0), 1f, 1.1f),
                Shot(115, 130, Key.Last, Key.Eye, Key.Last, Key.EyeLook, 1.1f, 1f),
            ),
            listOf(
                Caption(6, 44, line("death0")),
                Caption(50, 84, line("death1")),
            ),
            listOf(Card(86, 118, title("closed"), title("closed.sub"), Formatting.LIGHT_PURPLE)),
        )
    }

    private var active: Scene? = null
    private var clock = 0
    private var anchor = Vec3d.ZERO
    private var forward = Vec3d(0.0, 0.0, 1.0)
    private var right = Vec3d(1.0, 0.0, 0.0)
    private var bossId = 0
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
        val gone = clock > 5 && client.world?.getEntityById(bossId) == null
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
                (boss?.getLerpedPos(tickProgress) ?: anchor).add(0.0, key.up, 0.0)
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
        val from = anchor.add(0.0, 2.5, 0.0)
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

        val font = client.textRenderer
        for (caption in scene.captions) {
            if (t < caption.start || t > caption.end) continue
            val full = Text.translatable(caption.key).string
            // Schreibmaschine: Vael spricht langsam
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
