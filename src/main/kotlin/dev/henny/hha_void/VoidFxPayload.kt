package dev.henny.hha_void

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.math.Vec3d

/** Effekt-Arten des S2C-Pakets; die Bedeutung der Felder steht in `VoidVisuals`. */
object VoidFxKind {
    const val BLACK_HOLE = 0
    const val HOLE_TETHER = 1
    const val METEOR = 2
    const val COMET = 3
    const val COMET_IMPACT = 4
    const val SHARD = 5
    const val SHARD_END = 6
    const val PORTAL = 7
    const val STREAK = 8
    const val VORTEX = 9
    const val SHOCKWAVE = 10
    const val ARC = 11
    const val IMPACT = 12
    const val BURST = 13
    const val PILLAR = 14
    const val CHAIN = 15
    const val BEAM = 16
    const val CHARGE = 17
    const val NOVA = 18
    const val STOMP = 19
    const val SKY_STRIKE = 20
    const val RIFT = 21
    const val GROUND_CRACK = 22
    const val RIFT_FIST = 23
    const val RIFT_ROCK = 24
    const val SKY_BEAMS = 25
    const val CUTSCENE = 26
    const val HOLE_END = 27
    const val SHARD_RING = 28

    // Leviathan
    const val ZONE = 29
    const val WAVE = 30
    const val LINE = 31
    const val SCREEN = 32
    const val STARFALL = 33
    const val STAR_BREATH = 34
    const val HARPOON = 35
    const val HARPOON_END = 36
    const val RIDE_RELEASE = 37
    const val SECTOR = 38
}

/**
 * S2C: ein Server-Ereignis, das der Client als 3D-Effekt darstellt. Ein einziges
 * generisches Paket statt Dutzender Typen; die Spielmechanik bleibt komplett serverseitig.
 */
data class VoidFxPayload(
    val kind: Int,
    val id: Int = 0,
    val id2: Int = 0,
    val a: Vec3d = Vec3d.ZERO,
    val b: Vec3d = Vec3d.ZERO,
    val c: Vec3d = Vec3d.ZERO,
    val x: Float = 0f,
    val y: Float = 0f,
) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<VoidFxPayload>(VoidItems.id("fx"))

        val CODEC: PacketCodec<RegistryByteBuf, VoidFxPayload> = PacketCodec.of(
            { payload, buf ->
                buf.writeVarInt(payload.kind)
                buf.writeVarInt(payload.id)
                buf.writeVarInt(payload.id2)
                writeVec(buf, payload.a)
                writeVec(buf, payload.b)
                writeVec(buf, payload.c)
                buf.writeFloat(payload.x)
                buf.writeFloat(payload.y)
            },
            { buf ->
                VoidFxPayload(
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    readVec(buf), readVec(buf), readVec(buf),
                    buf.readFloat(), buf.readFloat(),
                )
            },
        )

        private fun writeVec(buf: RegistryByteBuf, vec: Vec3d) {
            buf.writeDouble(vec.x)
            buf.writeDouble(vec.y)
            buf.writeDouble(vec.z)
        }

        private fun readVec(buf: RegistryByteBuf) = Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
    }
}
