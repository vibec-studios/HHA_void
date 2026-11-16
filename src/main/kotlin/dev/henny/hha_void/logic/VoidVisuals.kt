package dev.henny.hha_void.logic

import dev.henny.hha_void.VoidFxKind
import dev.henny.hha_void.VoidFxPayload
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d

/** Server-Seite der 3D-Effekte: schickt Effekt-Ereignisse an alle Spieler in Sichtweite. */
object VoidVisuals {

    private const val VIEW_RANGE = 420.0
    private var nextId = 1

    fun newId(): Int = nextId++

    private fun send(world: ServerWorld, at: Vec3d, payload: VoidFxPayload) {
        for (player in PlayerLookup.around(world, at, VIEW_RANGE)) {
            if (ServerPlayNetworking.canSend(player, VoidFxPayload.ID)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    /** Schwarzes Loch bei [center], Sog-Wurzel am Boden bei [ground]. */
    fun blackHole(world: ServerWorld, id: Int, center: Vec3d, ground: Vec3d, durationTicks: Int) =
        send(world, center, VoidFxPayload(VoidFxKind.BLACK_HOLE, id, a = center, b = ground, x = durationTicks.toFloat()))

    /** Lässt ein schwarzes Loch vorzeitig zusammenfallen. */
    fun blackHoleEnd(world: ServerWorld, id: Int, at: Vec3d) =
        send(world, at, VoidFxPayload(VoidFxKind.HOLE_END, id, a = at))

    /** Energie-Fessel zwischen Loch [holeId] und dem Opfer [entityId]. */
    fun holeTether(world: ServerWorld, holeId: Int, entityId: Int, at: Vec3d) =
        send(world, at, VoidFxPayload(VoidFxKind.HOLE_TETHER, holeId, entityId))

    /** Hängt das 3D-Meteor-Modell an das Projektil [entityId]. */
    fun meteor(world: ServerWorld, entityId: Int, spawn: Vec3d, big: Boolean) =
        send(world, spawn, VoidFxPayload(VoidFxKind.METEOR, entityId, a = spawn, x = if (big) 1f else 0f))

    fun comet(world: ServerWorld, ground: Vec3d, durationTicks: Int) =
        send(world, ground, VoidFxPayload(VoidFxKind.COMET, a = ground, x = durationTicks.toFloat()))

    fun cometImpact(world: ServerWorld, center: Vec3d, radius: Double) =
        send(world, center, VoidFxPayload(VoidFxKind.COMET_IMPACT, a = center, x = radius.toFloat()))

    fun shard(world: ServerWorld, id: Int, pos: Vec3d, velocity: Vec3d) =
        send(world, pos, VoidFxPayload(VoidFxKind.SHARD, id, a = pos, b = velocity))

    /** Welche der kreisenden Schwert-Splitter von [playerId] gerade da sind (Bitmaske). */
    fun shardRing(world: ServerWorld, playerId: Int, mask: Int, at: Vec3d) =
        send(world, at, VoidFxPayload(VoidFxKind.SHARD_RING, playerId, mask, a = at))

    fun shardEnd(world: ServerWorld, id: Int, pos: Vec3d) =
        send(world, pos, VoidFxPayload(VoidFxKind.SHARD_END, id, a = pos))

    fun portal(world: ServerWorld, center: Vec3d, direction: Vec3d, scale: Float = 1f) =
        send(world, center, VoidFxPayload(VoidFxKind.PORTAL, a = center, b = direction, x = scale))

    fun streak(world: ServerWorld, from: Vec3d, to: Vec3d) =
        send(world, from, VoidFxPayload(VoidFxKind.STREAK, a = from, b = to))

    fun vortex(world: ServerWorld, feet: Vec3d) =
        send(world, feet, VoidFxPayload(VoidFxKind.VORTEX, a = feet))

    fun shockwave(world: ServerWorld, center: Vec3d, ground: Vec3d, range: Double) =
        send(world, center, VoidFxPayload(VoidFxKind.SHOCKWAVE, a = center, b = ground, x = range.toFloat()))

    /** Geschwungener Energiebogen über den Kontrollpunkt [control]; [tier] 0 ist die dezente Variante. */
    fun arc(world: ServerWorld, from: Vec3d, control: Vec3d, to: Vec3d, tier: Int, durationTicks: Int) =
        send(world, from, VoidFxPayload(VoidFxKind.ARC, tier, a = from, b = control, c = to, x = durationTicks.toFloat()))

    fun impact(world: ServerWorld, pos: Vec3d, size: Float) =
        send(world, pos, VoidFxPayload(VoidFxKind.IMPACT, a = pos, x = size))

    /** Kündigt [delayTicks] lang einen Kreis am Boden an, dann bricht eine Säule heraus. */
    fun pillar(world: ServerWorld, ground: Vec3d, delayTicks: Int, height: Float, light: Boolean = false) =
        send(world, ground, VoidFxPayload(VoidFxKind.PILLAR, if (light) 1 else 0, a = ground, x = delayTicks.toFloat(), y = height))

    /** Riss in der Luft bei [center], ausgerichtet entlang [direction]. */
    fun rift(world: ServerWorld, center: Vec3d, direction: Vec3d, length: Float, durationTicks: Int) =
        send(world, center, VoidFxPayload(VoidFxKind.RIFT, newId(), a = center, b = direction, x = length, y = durationTicks.toFloat()))

    /** Sprung im Boden, der in [runTicks] von [from] nach [to] läuft und [durationTicks] lang bleibt. */
    fun crack(world: ServerWorld, from: Vec3d, to: Vec3d, runTicks: Int, durationTicks: Int) =
        send(world, from, VoidFxPayload(VoidFxKind.GROUND_CRACK, newId(), runTicks, a = from, b = to, x = durationTicks.toFloat()))

    /** Sprünge sternförmig um [center]. */
    fun crackStar(world: ServerWorld, center: Vec3d, count: Int, reach: Double, runTicks: Int, durationTicks: Int) {
        for (i in 0 until count) {
            val angle = i * (Math.PI * 2.0 / count) + center.x * 0.37
            val to = center.add(kotlin.math.cos(angle) * reach, 0.0, kotlin.math.sin(angle) * reach)
            crack(world, center, to, runTicks, durationTicks)
        }
    }

    /** Vaels Faust schlägt aus einem Riss in [height] Blöcken Höhe herab. */
    fun riftFist(world: ServerWorld, ground: Vec3d, delayTicks: Int, height: Float) =
        send(world, ground, VoidFxPayload(VoidFxKind.RIFT_FIST, a = ground, x = delayTicks.toFloat(), y = height))

    /** Ein Brocken fällt aus einem Riss im Himmel. */
    fun riftRock(world: ServerWorld, ground: Vec3d, delayTicks: Int) =
        send(world, ground, VoidFxPayload(VoidFxKind.RIFT_ROCK, a = ground, x = delayTicks.toFloat()))

    /** Strahlen aus dem Himmel in die Trümmer bei [center]. */
    fun skyBeams(world: ServerWorld, center: Vec3d, durationTicks: Int) =
        send(world, center, VoidFxPayload(VoidFxKind.SKY_BEAMS, a = center, x = durationTicks.toFloat()))

    /** Startet eine Cutscene; [anchor] und [yaw] legen den Bezugspunkt der Kamera fest. */
    fun cutscene(world: ServerWorld, scene: Int, bossId: Int, anchor: Vec3d, yaw: Float) =
        send(world, anchor, VoidFxPayload(VoidFxKind.CUTSCENE, scene, bossId, a = anchor, x = yaw))

    /** Cutscene nur für einen Spieler (z. B. die Fahrt durch den Bauch für die Verschlungenen). */
    fun cutsceneTo(player: net.minecraft.server.network.ServerPlayerEntity, scene: Int, bossId: Int, anchor: Vec3d, yaw: Float) {
        if (ServerPlayNetworking.canSend(player, VoidFxPayload.ID)) {
            ServerPlayNetworking.send(player, VoidFxPayload(VoidFxKind.CUTSCENE, scene, bossId, a = anchor, x = yaw))
        }
    }

    /** Kette zwischen zwei Entities. */
    fun chain(world: ServerWorld, fromEntity: Int, toEntity: Int, at: Vec3d, durationTicks: Int) =
        send(world, at, VoidFxPayload(VoidFxKind.CHAIN, fromEntity, toEntity, a = at, x = durationTicks.toFloat()))

    /**
     * Rotierende Tilgungsbalken. [start] und [turn] (Radiant pro Tick) laufen auf Client und
     * Server gleich, damit der sichtbare Balken dort trifft, wo er gezeichnet wird.
     */
    fun beam(world: ServerWorld, origin: Vec3d, arms: Int, start: Double, turn: Double, length: Float, durationTicks: Int, warmupTicks: Int) =
        send(world, origin, VoidFxPayload(VoidFxKind.BEAM, arms, warmupTicks, a = origin, b = Vec3d(start, turn, durationTicks.toDouble()), x = length))

    /** Aufladung: Energie zieht sich [durationTicks] lang auf einen Punkt zusammen. */
    fun charge(world: ServerWorld, at: Vec3d, durationTicks: Int) =
        send(world, at, VoidFxPayload(VoidFxKind.CHARGE, a = at, x = durationTicks.toFloat()))

    fun nova(world: ServerWorld, at: Vec3d, size: Float) =
        send(world, at, VoidFxPayload(VoidFxKind.NOVA, a = at, x = size))

    /** Schritt des Golems: Kamera-Ruck, bei harten Landungen auch ein Ring im Staub. */
    fun stomp(world: ServerWorld, at: Vec3d, strength: Float) =
        send(world, at, VoidFxPayload(VoidFxKind.STOMP, a = at, x = strength))

    /** Strahl aus dem Himmel: erst Markierung am Boden, nach [delayTicks] der Einschlag. */
    fun skyStrike(world: ServerWorld, ground: Vec3d, delayTicks: Int) =
        send(world, ground, VoidFxPayload(VoidFxKind.SKY_STRIKE, a = ground, x = delayTicks.toFloat()))

    fun burst(world: ServerWorld, pos: Vec3d, strength: Int) =
        send(world, pos, VoidFxPayload(VoidFxKind.BURST, a = pos, x = strength.toFloat()))

    // ---------- Leviathan ----------

    /** Warnzone am Boden: Kreis mit Radius [radius], füllt sich [delayTicks] lang; [style] 0 = Gefahr, 1 = sicher (Lücke), 2 = Geysir. */
    fun zone(world: ServerWorld, ground: Vec3d, radius: Double, delayTicks: Int, style: Int = 0) =
        send(world, ground, VoidFxPayload(VoidFxKind.ZONE, style, a = ground, x = radius.toFloat(), y = delayTicks.toFloat()))

    /**
     * Flutwelle: Wand aus Leere, Mitte bei [start], läuft je Tick um [step]; [halfLength] quer, [height] hoch.
     * [gaps] = Versatz der Lücken quer zur Laufrichtung (x, y) und ihre halbe Breite (z). [warmupTicks] steht sie still.
     */
    fun wave(world: ServerWorld, start: Vec3d, step: Vec3d, gaps: Vec3d, halfLength: Double, height: Int, durationTicks: Int, warmupTicks: Int) =
        send(world, start, VoidFxPayload(VoidFxKind.WAVE, height, warmupTicks, a = start, b = step, c = gaps, x = halfLength.toFloat(), y = durationTicks.toFloat()))

    /** Markierte Bahn am Boden von [from] nach [to] (halbe Breite [halfWidth]), [delayTicks] Vorwarnung. */
    fun line(world: ServerWorld, from: Vec3d, to: Vec3d, halfWidth: Double, delayTicks: Int) =
        send(world, from, VoidFxPayload(VoidFxKind.LINE, a = from, b = to, x = halfWidth.toFloat(), y = delayTicks.toFloat()))

    /** Sternschnuppen fallen [durationTicks] lang über [center] (Radius [radius]). */
    fun starfall(world: ServerWorld, center: Vec3d, radius: Double, count: Int, durationTicks: Int) =
        send(world, center, VoidFxPayload(VoidFxKind.STARFALL, count, a = center, x = radius.toFloat(), y = durationTicks.toFloat()))

    /**
     * Sternenatem: Strahl aus dem Maul des Wals [bossId]. Während [charge] Ticks zeigt eine flackernde Linie am Boden
     * die Bahn von [from] nach [to], dann fegt der Strahl [fire] Ticks lang darüber ([passes] = 2: hin und zurück).
     * [width] = Dicke. Danach bleibt die Brandspur kurz liegen und zerspringt (die Splitter schickt der Server).
     */
    fun starBreath(world: ServerWorld, bossId: Int, from: Vec3d, to: Vec3d, charge: Int, fire: Int, passes: Int, width: Float) =
        send(world, from, VoidFxPayload(VoidFxKind.STAR_BREATH, bossId, passes, a = from, b = to, c = Vec3d(charge.toDouble(), fire.toDouble(), 0.0), x = width))

    /**
     * Harpune [tether] fliegt vom Geschütz [from] in [travel] Ticks zum Volumen [volume] des Wals [bossId] und bleibt
     * als gespanntes Seil bis [HARPOON_END] (oder höchstens [hold] Ticks).
     */
    fun harpoon(world: ServerWorld, tether: Int, bossId: Int, volume: Int, from: Vec3d, travel: Int, hold: Int) =
        send(world, from, VoidFxPayload(VoidFxKind.HARPOON, bossId, volume, a = from, c = Vec3d(tether.toDouble(), 0.0, 0.0), x = travel.toFloat(), y = hold.toFloat()))

    fun harpoonEnd(world: ServerWorld, tether: Int, at: Vec3d) =
        send(world, at, VoidFxPayload(VoidFxKind.HARPOON_END, tether, a = at))

    /** Der Spieler wurde vom Rücken des Wals geworfen: der Client trägt ihn [ticks] lang nicht mehr mit. */
    fun rideRelease(player: net.minecraft.server.network.ServerPlayerEntity, ticks: Int) {
        if (ServerPlayNetworking.canSend(player, VoidFxPayload.ID)) {
            ServerPlayNetworking.send(player, VoidFxPayload(VoidFxKind.RIDE_RELEASE, a = player.entityPos, x = ticks.toFloat()))
        }
    }

    /** Kreissektor am Boden (Winkel [a0]..[a1] im Bogenmaß, Radius [inner]..[outer]) leuchtet [delay] Ticks, dann bricht er auf. */
    fun sector(world: ServerWorld, center: Vec3d, a0: Double, a1: Double, inner: Double, outer: Double, delay: Int) =
        send(world, center, VoidFxPayload(VoidFxKind.SECTOR, a = center, b = Vec3d(a0, a1, inner), x = outer.toFloat(), y = delay.toFloat()))

    /**
     * Bildschirm eines Spielers: [mode] 0 = in [a] Ticks schwarz, [b] Ticks halten, dann aufhellen; 1 = weißer Blitz;
     * 2 = roter Puls; 3 = aus Schwarz aufhellen ([a] Ticks); 4 = Bauch-Vignette ([a] Ticks, pulsierend).
     */
    fun screen(player: net.minecraft.server.network.ServerPlayerEntity, mode: Int, a: Int, b: Int = 0) {
        if (ServerPlayNetworking.canSend(player, VoidFxPayload.ID)) {
            ServerPlayNetworking.send(player, VoidFxPayload(VoidFxKind.SCREEN, mode, a = player.entityPos, x = a.toFloat(), y = b.toFloat()))
        }
    }
}
