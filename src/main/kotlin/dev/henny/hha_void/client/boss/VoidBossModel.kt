package dev.henny.hha_void.client.boss

import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPart
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData
import net.minecraft.client.render.entity.model.EntityModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Vael als Koloss: Keilform mit offener Herzkammer, Gelenke aus Licht, Fäuste mit Fingern,
 * tief sitzender Kopf mit Kiefer. Jede Haltung liefert einen Satz Gelenkwerte ([Rig]); beim Wechsel wird weich
 * überblendet, Laufen, Schlag und Treffer kommen obendrauf.
 */
class VoidBossModel(root: ModelPart) : EntityModel<VoidBossRenderState>(root) {

    private val body = root.getChild("body")
    private val head = body.getChild("head")
    private val heart = body.getChild("heart")
    private val rebornSpikes = body.getChild("rebornSpikes")
    private val rightArm = body.getChild("rightArm")
    private val leftArm = body.getChild("leftArm")
    private val rightFore = rightArm.getChild("rightFore")
    private val leftFore = leftArm.getChild("leftFore")
    private val rightHand = rightFore.getChild("rightHand")
    private val leftHand = leftFore.getChild("leftHand")
    private val rightLeg = root.getChild("rightLeg")
    private val leftLeg = root.getChild("leftLeg")
    private val rightShin = rightLeg.getChild("rightShin")
    private val leftShin = leftLeg.getChild("leftShin")
    private val tome = root.getChild("tome")
    private val jaw = head.getChild("jaw")
    private val crystals = body.getChild("crystals")
    private val rightFingers = rightHand.getChild("rightFingers")
    private val leftFingers = leftHand.getChild("leftFingers")
    private val orbitA = root.getChild("orbitA")
    private val orbitB = root.getChild("orbitB")
    private val orbitC = root.getChild("orbitC")

    /** Alle Gelenkwerte einer Haltung. */
    private class Rig {
        var bodyPitch = 0f
        var bodyYaw = 0f
        var bodyRoll = 0f
        var crouch = 0f
        var headPitch = 0f
        var headYaw = 0f
        var headRoll = 0f
        var rPitch = 0f
        var rYaw = 0f
        var rRoll = 0f
        var rFore = 0f
        var lPitch = 0f
        var lYaw = 0f
        var lRoll = 0f
        var lFore = 0f
        var rLeg = 0f
        var rKnee = 0f
        var lLeg = 0f
        var lKnee = 0f
        var heartSize = 1f
        var tremble = 0f

        var jaw = 0f

        /** 0 = Faust geballt, 1 = Hand offen. */
        var grip = 0f

        /** Zerfall je Körperteil: 0 = heil, 1 = liegt in Trümmern. */
        var scatterLegs = 0f
        var scatterBody = 0f
        var scatterArms = 0f
        var scatterHead = 0f

        /** Vael steckt noch im Riss: kein Modell zu sehen. */
        var hidden = false

        /** Die rechte Faust steckt gerade in einem Riss. */
        var handInRift = false

        fun scatter(k: Float) {
            scatterLegs = k
            scatterBody = k
            scatterArms = k
            scatterHead = k
        }

        fun mixFrom(a: Rig, k: Float) {
            fun m(x: Float, y: Float) = x + (y - x) * k
            bodyPitch = m(a.bodyPitch, bodyPitch); bodyYaw = m(a.bodyYaw, bodyYaw); bodyRoll = m(a.bodyRoll, bodyRoll)
            crouch = m(a.crouch, crouch)
            headPitch = m(a.headPitch, headPitch); headYaw = m(a.headYaw, headYaw); headRoll = m(a.headRoll, headRoll)
            rPitch = m(a.rPitch, rPitch); rYaw = m(a.rYaw, rYaw); rRoll = m(a.rRoll, rRoll); rFore = m(a.rFore, rFore)
            lPitch = m(a.lPitch, lPitch); lYaw = m(a.lYaw, lYaw); lRoll = m(a.lRoll, lRoll); lFore = m(a.lFore, lFore)
            rLeg = m(a.rLeg, rLeg); rKnee = m(a.rKnee, rKnee); lLeg = m(a.lLeg, lLeg); lKnee = m(a.lKnee, lKnee)
            heartSize = m(a.heartSize, heartSize); tremble = m(a.tremble, tremble)
            jaw = m(a.jaw, jaw); grip = m(a.grip, grip)
            scatterLegs = m(a.scatterLegs, scatterLegs); scatterBody = m(a.scatterBody, scatterBody)
            scatterArms = m(a.scatterArms, scatterArms); scatterHead = m(a.scatterHead, scatterHead)
        }
    }

    private fun smooth(from: Float, to: Float, x: Float): Float {
        val k = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    /** Ruhehaltung: breitbeinig, Fäuste hängen schwer, langsames Atmen. */
    private fun idle(t: Float) = Rig().apply {
        val breath = sin(t * 0.05f)
        bodyPitch = 0.12f + breath * 0.015f
        bodyRoll = sin(t * 0.025f) * 0.012f
        headPitch = 0.02f
        headYaw = sin(t * 0.018f) * 0.15f
        rPitch = -0.05f + breath * 0.03f
        rRoll = 0.06f
        rFore = -0.15f
        lPitch = -0.05f - breath * 0.03f
        lRoll = -0.06f
        lFore = -0.15f
        heartSize = 1f + (sin(t * 0.2f).coerceAtLeast(0.75f) - 0.75f) * 0.8f
    }

    private fun rig(pose: Int, age: Float, t: Float): Rig {
        val r = idle(t)
        when (pose) {
            VoidBossPoses.VERDICT -> { // Holt mit der rechten Faust aus und rammt sie in den Boden
                val c = (age % 14f) / 14f
                val up = smooth(0f, 0.55f, c)
                val hit = smooth(0.55f, 0.68f, c)
                val back = 1f - smooth(0.72f, 1f, c)
                r.rPitch = lerp(lerp(-0.1f, -2.8f, up), -0.95f, hit)
                r.rFore = lerp(-0.6f, -0.05f, hit)
                r.bodyPitch += hit * back * 0.5f - up * (1f - hit) * 0.15f
                r.crouch = hit * back * 3f
                r.rKnee = hit * back * 0.5f
                r.lKnee = hit * back * 0.5f
                r.rLeg = -hit * back * 0.25f
                r.lLeg = -hit * back * 0.25f
            }
            VoidBossPoses.VOLLEY -> { // Schleudert Splitter abwechselnd aus den Fäusten
                val right = (age / 5f).toInt() % 2 == 0
                val thrust = sin((age % 5f) / 5f * PI.toFloat())
                val forward = -1.15f - thrust * 0.5f
                r.rPitch = if (right) forward else -0.6f
                r.lPitch = if (right) -0.6f else forward
                r.rFore = if (right) -0.05f else -0.7f
                r.lFore = if (right) -0.7f else -0.05f
                r.bodyYaw = if (right) -0.2f * thrust else 0.2f * thrust
            }
            VoidBossPoses.COLLECT -> { // Streckt die Faust aus und zieht die Kette zu sich
                val reach = smooth(0f, 8f, age)
                r.grip = 1f - smooth(10f, 16f, age)
                val pull = smooth(8f, 44f, age)
                r.lPitch = lerp(-0.1f, lerp(-1.6f, -0.7f, pull), reach)
                r.lFore = lerp(-0.2f, lerp(-0.05f, -1.5f, pull), reach)
                r.bodyPitch = lerp(r.bodyPitch, -0.08f, pull)
                r.bodyYaw = -0.25f * pull
                r.rKnee = 0.25f * pull
                r.tremble = 0.012f
            }
            VoidBossPoses.SEIZURE -> { // Beide Fäuste greifen vor und krachen zusammen
                val reach = smooth(0f, 14f, age)
                r.grip = reach * (1f - smooth(16f, 19f, age))
                val snap = smooth(17f, 20f, age)
                r.rPitch = lerp(-0.1f, lerp(-1.4f, -1.55f, snap), reach)
                r.lPitch = r.rPitch
                r.rYaw = lerp(0f, lerp(0.15f, 0.75f, snap), reach)
                r.lYaw = -r.rYaw
                r.bodyPitch = lerp(r.bodyPitch, 0.32f, reach)
                r.crouch = reach * 1.5f
            }
            VoidBossPoses.SWEEP -> { // Dreht sich mit ausgestreckten Armen auf der Stelle
                val open = smooth(0f, 14f, age)
                r.rRoll = lerp(0.1f, 1.5f, open)
                r.lRoll = lerp(-0.1f, -1.5f, open)
                r.rPitch = 0f
                r.lPitch = 0f
                r.rFore = -0.05f
                r.lFore = -0.05f
                r.bodyYaw = smooth(10f, 30f, age) * age * 0.32f
                val stamp = sin(age * 0.6f)
                r.rLeg = stamp * 0.12f
                r.lLeg = -stamp * 0.12f
            }
            VoidBossPoses.SINGULARITY -> { // Reißt beide Arme hoch, der Blick in den Himmel
                val raise = smooth(0f, 12f, age)
                r.grip = raise
                r.jaw = raise * 0.4f
                r.rPitch = lerp(-0.1f, -3f, raise)
                r.lPitch = r.rPitch
                r.rRoll = 0.3f
                r.lRoll = -0.3f
                r.rFore = -0.25f
                r.lFore = -0.25f
                r.headPitch = lerp(r.headPitch, -0.75f, raise)
                r.bodyPitch = lerp(r.bodyPitch, -0.15f, raise)
                r.heartSize = 1.4f + sin(t * 0.6f) * 0.2f
            }
            VoidBossPoses.REMISSION -> { // Stemmt sich breit auf, lädt und bebt immer stärker
                val k = (age / 110f).coerceIn(0f, 1f)
                r.grip = 1f
                r.jaw = k * 0.8f
                r.rRoll = lerp(0.5f, 1.75f, smooth(0f, 0.4f, k))
                r.lRoll = -r.rRoll
                r.rPitch = -0.1f
                r.lPitch = -0.1f
                r.rFore = -0.1f
                r.lFore = -0.1f
                r.headPitch = lerp(0f, -0.6f, k)
                r.bodyPitch = lerp(0f, -0.25f, k)
                r.heartSize = 1f + k * 1.3f + sin(t * (0.3f + k)) * 0.15f
                r.crouch = k * 1.5f
                r.rKnee = k * 0.3f
                r.lKnee = k * 0.3f
                r.tremble = 0.008f + k * 0.05f
            }
            VoidBossPoses.ROAR -> { // Schlägt sich auf die Brust und brüllt
                val k = smooth(0f, 8f, age)
                val beat = kotlin.math.abs(sin(age * 0.55f))
                r.jaw = k * (0.7f + sin(t * 1.3f) * 0.08f)
                r.rPitch = lerp(-0.1f, -1.9f + beat * 0.5f, k)
                r.lPitch = lerp(-0.1f, -1.9f + (1f - beat) * 0.5f, k)
                r.rYaw = 0.75f * k
                r.lYaw = -0.75f * k
                r.rFore = -1.2f * k
                r.lFore = -1.2f * k
                r.headPitch = lerp(0f, -0.65f, k)
                r.bodyPitch = lerp(0.3f, -0.22f, k)
                r.heartSize = 1.5f
                r.tremble = 0.025f
            }
            VoidBossPoses.INTRO -> { // Steht aus der Hocke auf
                val k = smooth(8f, 46f, age)
                r.bodyPitch = lerp(0.9f, r.bodyPitch, k)
                r.headPitch = lerp(0.5f, r.headPitch, k)
                r.rPitch = lerp(-0.85f, r.rPitch, k)
                r.lPitch = lerp(-0.85f, r.lPitch, k)
                r.crouch = lerp(7f, 0f, k)
                r.rKnee = lerp(1.3f, 0f, k)
                r.lKnee = lerp(1.3f, 0f, k)
                r.rLeg = lerp(-0.65f, 0f, k)
                r.lLeg = lerp(-0.65f, 0f, k)
                r.heartSize = lerp(0.3f, r.heartSize, k)
            }
            VoidBossPoses.SKY -> { // Ruft den Himmel herab: eine Faust zum Zenit, dann niederreißen
                val c = (age % 22f) / 22f
                val up = smooth(0f, 0.45f, c)
                r.grip = up
                r.jaw = up * 0.3f
                val down = smooth(0.7f, 0.82f, c)
                r.rPitch = lerp(lerp(-0.1f, -3.05f, up), -1.3f, down)
                r.rFore = -0.1f
                r.headPitch = lerp(-0.2f, -0.7f, up) + down * 0.5f
                r.bodyPitch = lerp(r.bodyPitch, -0.12f, up) + down * 0.3f
                r.lRoll = -0.4f
            }
            VoidBossPoses.FISSURE -> { // Stampft mit dem Fuß: der Riss läuft auf dich zu
                val lift = smooth(0f, 10f, age)
                val stomp = smooth(13f, 16f, age)
                r.rLeg = lerp(-1.1f * lift, -0.1f, stomp)
                r.rKnee = lerp(1.1f * lift, 0.1f, stomp)
                r.lKnee = 0.25f * lift * (1f - stomp)
                r.bodyPitch = lerp(-0.2f * lift, 0.32f, stomp)
                r.bodyRoll = lerp(-0.12f * lift, 0.05f, stomp)
                r.crouch = stomp * 2.5f
                r.rPitch = lerp(0.3f, -0.3f, stomp)
                r.lPitch = lerp(-0.5f, 0.2f, stomp)
            }
            VoidBossPoses.FALSE_DEATH -> { // Bricht in die Knie, dann fällt er Stück für Stück auseinander
                val knees = smooth(0f, 16f, age)
                r.crouch = lerp(0f, 8f, knees)
                r.rKnee = 1.5f * knees
                r.lKnee = 1.5f * knees
                r.rLeg = -0.75f * knees
                r.lLeg = -0.75f * knees
                r.bodyPitch = lerp(r.bodyPitch, 0.55f, knees)
                r.headPitch = lerp(r.headPitch, 0.6f, knees)
                r.rPitch = 0.1f
                r.lPitch = 0.1f
                r.grip = knees
                r.jaw = knees * 0.5f
                r.heartSize = lerp(1f, 0f, smooth(14f, 30f, age))
                r.tremble = 0.02f * (1f - smooth(18f, 36f, age))
                r.scatterArms = smooth(16f, 26f, age)
                r.scatterHead = smooth(22f, 32f, age)
                r.scatterBody = smooth(24f, 36f, age)
                r.scatterLegs = smooth(28f, 40f, age)
            }
            VoidBossPoses.STIR -> { // Die Trümmer zucken: da lebt noch etwas
                r.scatter(1f - abs(sin(age * 0.8f)) * 0.05f * smooth(0f, 20f, age))
                r.heartSize = 0.3f + abs(sin(age * 0.4f)) * 0.4f
                r.grip = 1f
                r.jaw = 0.5f
            }
            VoidBossPoses.REASSEMBLE -> { // Erst die Beine, dann Rumpf und Arme, zuletzt der Kopf
                val k = smooth(30f, 100f, age)
                val ruck = abs(sin(age * 1.2f)) * 0.08f
                r.scatterLegs = (1f - smooth(5f, 40f, age)) * (1f - ruck)
                r.scatterBody = (1f - smooth(25f, 65f, age)) * (1f - ruck)
                r.scatterArms = (1f - smooth(45f, 85f, age)) * (1f - ruck)
                r.scatterHead = (1f - smooth(65f, 100f, age)) * (1f - ruck)
                r.crouch = lerp(8f, 0f, k)
                r.rKnee = lerp(1.5f, 0f, k)
                r.lKnee = lerp(1.5f, 0f, k)
                r.rLeg = lerp(-0.75f, 0f, k)
                r.lLeg = lerp(-0.75f, 0f, k)
                r.bodyPitch = lerp(0.7f, -0.1f, k)
                r.headPitch = lerp(0.6f, -0.3f, k)
                r.heartSize = lerp(0.3f, 1.9f, k)
                r.grip = 1f - k
                r.tremble = 0.05f
            }
            VoidBossPoses.DEATH -> { // Das echte Ende: er kniet, sieht auf und zerbröckelt langsam
                val knees = smooth(0f, 24f, age)
                r.crouch = lerp(0f, 8f, knees)
                r.rKnee = 1.5f * knees
                r.lKnee = 1.5f * knees
                r.rLeg = -0.75f * knees
                r.lLeg = -0.75f * knees
                r.bodyPitch = lerp(r.bodyPitch, 0.5f, knees)
                r.headPitch = lerp(r.headPitch, -0.55f, knees)
                r.rRoll = 0.5f * knees
                r.lRoll = -0.5f * knees
                r.grip = knees
                r.jaw = knees * 0.35f
                r.heartSize = 1f + knees * 1.2f - smooth(60f, 105f, age) * 2.2f
                r.tremble = 0.02f + knees * 0.03f
                r.scatterArms = smooth(60f, 85f, age)
                r.scatterHead = smooth(80f, 100f, age)
                r.scatterBody = smooth(90f, 112f, age)
                r.scatterLegs = smooth(95f, 118f, age)
            }
            VoidBossPoses.HOLD -> r.hidden = true
            VoidBossPoses.FALL -> { // Stürzt aus dem Riss: Arme hoch, Beine angezogen
                r.rPitch = -2.75f
                r.lPitch = -2.75f
                r.rRoll = 0.55f
                r.lRoll = -0.55f
                r.rFore = -0.2f
                r.lFore = -0.2f
                r.bodyPitch = 0.3f
                r.rLeg = -0.5f
                r.rKnee = 1f
                r.lLeg = -0.9f
                r.lKnee = 1.3f
                r.jaw = 0.5f
                r.grip = 1f
            }
            VoidBossPoses.LAND -> { // Schlägt auf: rechte Faust im Boden, Kopf hoch, dann richtet er sich auf
                val rise = smooth(34f, 58f, age)
                r.crouch = lerp(12f, 0f, rise)
                r.rKnee = lerp(1.7f, 0f, rise)
                r.lKnee = lerp(1.7f, 0f, rise)
                r.rLeg = lerp(-0.85f, 0f, rise)
                r.lLeg = lerp(-0.85f, 0f, rise)
                r.bodyPitch = lerp(1f, r.bodyPitch, rise)
                r.headPitch = lerp(-0.6f, r.headPitch, rise)
                r.rPitch = lerp(-1f, r.rPitch, rise)
                r.rRoll = lerp(0.2f, r.rRoll, rise)
                r.rFore = lerp(-0.1f, r.rFore, rise)
                r.lPitch = lerp(0.8f, r.lPitch, rise)
                r.lRoll = lerp(-0.75f, r.lRoll, rise)
                r.heartSize = lerp(0.3f, 1.2f, smooth(10f, 40f, age))
                r.tremble = 0.012f * (1f - rise)
            }
            VoidBossPoses.RIP -> { // Greift mit offenen Händen in die Luft und reißt sie auseinander
                val reach = smooth(0f, 7f, age)
                val pull = smooth(9f, 14f, age)
                r.rPitch = lerp(-0.1f, -1.5f, reach)
                r.lPitch = r.rPitch
                r.rFore = -0.1f
                r.lFore = -0.1f
                r.rYaw = lerp(0.35f, -0.85f, pull) * reach
                r.lYaw = -r.rYaw
                r.grip = 1f
                r.bodyPitch = lerp(r.bodyPitch, 0.28f, reach)
                r.jaw = pull * 0.5f
                r.rLeg = -0.45f * pull
                r.rKnee = 0.5f * pull
            }
            VoidBossPoses.EXIT -> { // Kommt mit erhobenen Fäusten aus dem Riss und schlägt zu
                val hit = smooth(3f, 6f, age)
                val back = 1f - smooth(14f, 26f, age)
                r.rPitch = lerp(-2.9f, -1.05f, hit)
                r.lPitch = r.rPitch
                r.rRoll = 0.15f
                r.lRoll = -0.15f
                r.bodyPitch = lerp(-0.25f, 0.75f, hit * back)
                r.crouch = hit * back * 6f
                r.rKnee = hit * back * 0.9f
                r.lKnee = hit * back * 0.9f
                r.rLeg = -hit * back * 0.45f
                r.lLeg = -hit * back * 0.45f
                r.jaw = 0.5f * back
            }
            VoidBossPoses.REACH -> { // Rammt den Arm in einen Riss: die Faust kommt über dir wieder heraus
                val wind = smooth(0f, 10f, age)
                val punch = smooth(11f, 15f, age)
                val out = smooth(36f, 44f, age)
                r.rPitch = lerp(lerp(-0.1f, 0.7f, wind), -1.62f, punch)
                r.rFore = lerp(-0.7f * wind, -0.02f, punch)
                r.rRoll = 0.02f
                r.bodyYaw = lerp(0.3f * wind, -0.35f, punch) * (1f - out)
                r.bodyPitch = lerp(r.bodyPitch, 0.26f, punch) * (1f - out) + r.bodyPitch * out
                r.rPitch = lerp(r.rPitch, -0.05f, out)
                r.lPitch = 0.35f * punch * (1f - out)
                r.handInRift = age > 14f && age < 40f
            }
        }
        return r
    }

    override fun setAngles(state: VoidBossRenderState) {
        super.setAngles(state)
        val t = state.age

        val rig = rig(state.pose, state.poseAge, t)
        val blendTicks = if (state.pose == VoidBossPoses.LAND || state.pose == VoidBossPoses.EXIT) 1.5f else BLEND_TICKS
        val blend = smooth(0f, blendTicks, state.poseAge)
        if (blend < 1f) rig.mixFrom(rig(state.previousPose, state.previousPoseAge, t), blend)

        // Schwerer Gang: Beine, Knie und gegenläufige Arme; beim Auftreten sackt der Rumpf ein
        val walk = state.limbSwingAnimationProgress * 0.55f
        val amount = state.limbSwingAmplitude.coerceAtMost(1f) * (1f - rig.scatterBody)
        val stepR = sin(walk)
        val stepL = -stepR
        rig.rLeg += stepR * 0.7f * amount
        rig.lLeg += stepL * 0.7f * amount
        rig.rKnee += max(0f, -cos(walk)) * 0.9f * amount
        rig.lKnee += max(0f, cos(walk)) * 0.9f * amount
        rig.rPitch -= stepR * 0.5f * amount
        rig.lPitch -= stepL * 0.5f * amount
        rig.bodyRoll += stepR * 0.05f * amount
        rig.bodyYaw += stepR * 0.09f * amount
        rig.headYaw -= stepR * 0.05f * amount
        rig.crouch += abs(cos(walk)) * 1.3f * amount
        rig.bodyPitch += 0.12f * amount

        // Wankt bei Treffern
        val flinch = state.flinch * state.flinch
        rig.bodyPitch -= flinch * 0.1f
        rig.headPitch -= flinch * 0.2f
        rig.rRoll += flinch * 0.12f
        rig.lRoll -= flinch * 0.12f

        // Faustschlag: Ausholen aus der Schulter, der Rumpf dreht mit
        val swing = sin(state.swing * PI.toFloat())
        val windup = sin(state.swing.coerceAtMost(0.35f) / 0.35f * PI.toFloat())
        rig.rPitch -= swing * 1.9f
        rig.rYaw += swing * 0.4f
        rig.rFore -= windup * 0.7f
        rig.bodyYaw -= swing * 0.5f
        rig.bodyPitch += swing * 0.3f

        val shake = if (rig.tremble > 0f) sin(t * 3.7f) * rig.tremble else 0f

        body.originY += rig.crouch
        body.pitch = rig.bodyPitch + shake
        body.yaw = rig.bodyYaw
        body.roll = rig.bodyRoll + shake * 0.7f

        head.yaw = state.relativeHeadYaw * RAD * (1f - rig.scatterHead) + rig.headYaw
        head.pitch = state.pitch * RAD * (1f - rig.scatterHead) + rig.headPitch
        head.roll = rig.headRoll + shake

        rightArm.pitch = rig.rPitch
        rightArm.yaw = rig.rYaw
        rightArm.roll = rig.rRoll + shake
        rightFore.pitch = rig.rFore
        leftArm.pitch = rig.lPitch
        leftArm.yaw = rig.lYaw
        leftArm.roll = rig.lRoll - shake
        leftFore.pitch = rig.lFore

        rightLeg.pitch = rig.rLeg
        rightShin.pitch = rig.rKnee
        leftLeg.pitch = rig.lLeg
        leftShin.pitch = rig.lKnee

        jaw.pitch = rig.jaw * 0.7f
        rightFingers.pitch = 1.15f * (1f - rig.grip)
        leftFingers.pitch = 1.15f * (1f - rig.grip)
        rightHand.visible = !rig.handInRift

        // Zerfallen: Rumpf kippt nach vorn auf den Boden, Arme, Kopf und Beine liegen drumherum
        if (rig.scatterBody > 0f) {
            val s = rig.scatterBody
            body.originY += s * 14f
            body.pitch = lerp(body.pitch, 1.42f, s)
            body.roll += s * 0.14f
        }
        if (rig.scatterArms > 0f) {
            val s = rig.scatterArms
            rightArm.originX -= s * 9f
            rightArm.originY += s * 8f
            rightArm.originZ -= s * 2f
            rightArm.pitch = lerp(rightArm.pitch, 1.05f, s)
            rightArm.roll = lerp(rightArm.roll, 1.1f, s)
            rightFore.pitch = lerp(rightFore.pitch, -0.7f, s)
            rightFore.originY += s * 3f
            rightHand.originY += s * 4f
            rightHand.roll = s * 0.8f
            leftArm.originX += s * 8f
            leftArm.originY += s * 3f
            leftArm.originZ += s * 7f
            leftArm.pitch = lerp(leftArm.pitch, 1.75f, s)
            leftArm.roll = lerp(leftArm.roll, -0.9f, s)
            leftFore.pitch = lerp(leftFore.pitch, -1.1f, s)
            leftFore.originY += s * 2f
            leftHand.originY += s * 5f
            leftHand.yaw = s * 0.9f
        }
        if (rig.scatterHead > 0f) {
            val s = rig.scatterHead
            head.originY -= s * 14f
            head.originZ -= s * 6f
            head.originX += s * 8f
            head.pitch = lerp(head.pitch, -1.2f, s)
            head.roll += s * 0.7f
        }
        if (rig.scatterLegs > 0f) {
            val s = rig.scatterLegs
            rightLeg.originX -= s * 4f
            rightLeg.originY += s * 14f
            rightLeg.originZ += s * 8f
            rightLeg.pitch = lerp(rightLeg.pitch, 1.5f, s)
            rightLeg.roll = s * 0.45f
            rightShin.pitch = lerp(rightShin.pitch, 0.5f, s)
            leftLeg.originX += s * 5f
            leftLeg.originY += s * 13f
            leftLeg.originZ += s * 5f
            leftLeg.pitch = lerp(leftLeg.pitch, 1.5f, s)
            leftLeg.roll = -s * 0.5f
            leftShin.pitch = lerp(leftShin.pitch, 0.3f, s)
            crystals.yScale = 1f - s * 0.3f
        }

        heart.xScale = rig.heartSize
        heart.yScale = rig.heartSize
        heart.zScale = rig.heartSize

        rebornSpikes.visible = state.phase >= 4
        if (state.phase >= 4) {
            val pulse = 1f + sin(t * 0.3f) * 0.04f
            rebornSpikes.yScale = pulse * (1f - rig.scatterBody * 0.8f)
        }

        // Das Hauptbuch kreist weiter um ihn
        val fallen = rig.scatterBody
        val orbit = t * 0.035f
        tome.originX = cos(orbit) * 30f
        tome.originZ = sin(orbit) * 30f
        tome.originY = -6f + sin(t * 0.09f) * 2f + fallen * 27f
        tome.yaw = -orbit + 1.57f
        tome.pitch = sin(t * 0.05f) * 0.15f + fallen * 1.4f

        // Trümmer schweben um die Schultern; beim Zerfall fallen sie herab
        val spin = t * 0.06f
        orbitA.originX = cos(spin) * 31f
        orbitA.originZ = sin(spin) * 31f
        orbitA.originY = -18f + sin(t * 0.07f) * 3f + fallen * 40f
        orbitA.yaw = spin * 2f
        orbitB.originX = cos(spin + 2.2f) * 29f
        orbitB.originZ = sin(spin + 2.2f) * 29f
        orbitB.originY = -12f + sin(t * 0.08f + 2f) * 3f + fallen * 34f
        orbitB.pitch = spin * 1.5f
        orbitC.originX = cos(spin + 4.3f) * 33f
        orbitC.originZ = sin(spin + 4.3f) * 33f
        orbitC.originY = -24f + sin(t * 0.06f + 4f) * 4f + fallen * 46f
        orbitC.roll = spin * 2.4f

        val shown = !rig.hidden
        body.visible = shown
        rightLeg.visible = shown
        leftLeg.visible = shown
        tome.visible = shown
        orbitA.visible = shown
        orbitB.visible = shown
        orbitC.visible = shown
    }

    companion object {
        private const val RAD = 0.017453292f
        private const val BLEND_TICKS = 6f

        fun texturedModelData(): TexturedModelData {
            val modelData = ModelData()
            val root = modelData.root
            val bodyData = root.addChild(
                "body",
                ModelPartBuilder.create()
                    .uv(0, 0).cuboid(-7.0f, -4.0f, -5.0f, 14.0f, 5.0f, 10.0f)
                    .uv(48, 0).cuboid(-8.0f, -2.0f, -6.0f, 16.0f, 2.0f, 12.0f)
                    .uv(104, 0).cuboid(-10.0f, -13.0f, -6.0f, 20.0f, 9.0f, 12.0f)
                    .uv(168, 0).cuboid(-15.0f, -26.0f, -8.0f, 9.0f, 13.0f, 16.0f)
                    .uv(0, 29).cuboid(6.0f, -26.0f, -8.0f, 9.0f, 13.0f, 16.0f)
                    .uv(50, 29).cuboid(-6.0f, -26.0f, -3.0f, 12.0f, 13.0f, 11.0f)
                    .uv(96, 29).cuboid(-6.0f, -26.0f, -8.0f, 12.0f, 3.0f, 5.0f)
                    .uv(130, 29).cuboid(-6.0f, -15.0f, -8.0f, 12.0f, 2.0f, 5.0f)
                    .uv(164, 29).cuboid(-6.0f, -23.0f, -4.0f, 12.0f, 8.0f, 1.0f)
                    .uv(190, 29).cuboid(-6.0f, -21.0f, -8.0f, 12.0f, 1.0f, 1.0f)
                    .uv(216, 29).cuboid(-6.0f, -18.0f, -8.0f, 12.0f, 1.0f, 1.0f)
                    .uv(242, 29).cuboid(-1.0f, -23.0f, -8.0f, 2.0f, 8.0f, 1.0f)
                    .uv(0, 58).cuboid(-13.0f, -29.0f, -6.0f, 26.0f, 3.0f, 12.0f)
                    .uv(76, 58).cuboid(-10.0f, -31.0f, 0.0f, 20.0f, 5.0f, 8.0f),
                ModelTransform.origin(0.0f, 7.0f, 0.0f),
            )
            val heartData = bodyData.addChild(
                "heart",
                ModelPartBuilder.create()
                    .uv(132, 58).cuboid(-3.0f, -3.0f, -2.0f, 6.0f, 6.0f, 4.0f),
                ModelTransform.origin(0.0f, -19.0f, -5.0f),
            )
            val crystalsData = bodyData.addChild(
                "crystals",
                ModelPartBuilder.create()
                    .uv(152, 58).cuboid(-2.0f, -11.0f, -2.0f, 4.0f, 11.0f, 4.0f)
                    .uv(168, 58).cuboid(-1.0f, -15.0f, -1.0f, 2.0f, 4.0f, 2.0f)
                    .uv(176, 58).cuboid(-8.0f, -7.0f, -1.0f, 3.0f, 7.0f, 3.0f)
                    .uv(188, 58).cuboid(-7.0f, -10.0f, 0.0f, 1.0f, 3.0f, 1.0f)
                    .uv(192, 58).cuboid(5.0f, -8.0f, -1.0f, 3.0f, 8.0f, 3.0f)
                    .uv(204, 58).cuboid(6.0f, -12.0f, 0.0f, 1.0f, 4.0f, 1.0f)
                    .uv(208, 58).cuboid(-4.0f, -5.0f, 2.0f, 2.0f, 5.0f, 2.0f)
                    .uv(216, 58).cuboid(3.0f, -4.0f, 2.0f, 2.0f, 4.0f, 2.0f),
                ModelTransform.origin(0.0f, -31.0f, 4.0f),
            )
            val rebornSpikesData = bodyData.addChild(
                "rebornSpikes",
                ModelPartBuilder.create()
                    .uv(224, 58).cuboid(-14.0f, -16.0f, -1.0f, 4.0f, 16.0f, 4.0f)
                    .uv(240, 58).cuboid(-13.0f, -21.0f, 0.0f, 2.0f, 5.0f, 2.0f)
                    .uv(0, 78).cuboid(10.0f, -18.0f, -1.0f, 4.0f, 18.0f, 4.0f)
                    .uv(16, 78).cuboid(11.0f, -24.0f, 0.0f, 2.0f, 6.0f, 2.0f)
                    .uv(24, 78).cuboid(-3.0f, -26.0f, 2.0f, 6.0f, 26.0f, 5.0f)
                    .uv(46, 78).cuboid(-1.0f, -33.0f, 3.0f, 2.0f, 7.0f, 3.0f)
                    .uv(56, 78).cuboid(-20.0f, -8.0f, -4.0f, 3.0f, 8.0f, 3.0f)
                    .uv(68, 78).cuboid(17.0f, -8.0f, -4.0f, 3.0f, 8.0f, 3.0f),
                ModelTransform.origin(0.0f, -29.0f, 2.0f),
            )
            val headData = bodyData.addChild(
                "head",
                ModelPartBuilder.create()
                    .uv(80, 78).cuboid(-6.0f, -9.0f, -6.0f, 12.0f, 9.0f, 11.0f)
                    .uv(126, 78).cuboid(-7.0f, -9.0f, -7.0f, 14.0f, 2.0f, 3.0f)
                    .uv(160, 78).cuboid(-7.0f, -5.0f, -4.0f, 1.0f, 4.0f, 6.0f)
                    .uv(174, 78).cuboid(6.0f, -5.0f, -4.0f, 1.0f, 4.0f, 6.0f)
                    .uv(188, 78).cuboid(-8.0f, -14.0f, -2.0f, 3.0f, 6.0f, 3.0f)
                    .uv(200, 78).cuboid(-9.0f, -18.0f, -1.0f, 2.0f, 4.0f, 2.0f)
                    .uv(208, 78).cuboid(5.0f, -14.0f, -2.0f, 3.0f, 6.0f, 3.0f)
                    .uv(220, 78).cuboid(7.0f, -18.0f, -1.0f, 2.0f, 4.0f, 2.0f)
                    .uv(228, 78).cuboid(-1.0f, -13.0f, -4.0f, 2.0f, 4.0f, 2.0f),
                ModelTransform.origin(0.0f, -26.0f, -7.0f),
            )
            val jawData = headData.addChild(
                "jaw",
                ModelPartBuilder.create()
                    .uv(0, 109).cuboid(-5.0f, 0.0f, -7.0f, 10.0f, 3.0f, 10.0f),
                ModelTransform.origin(0.0f, -1.0f, 1.0f),
            )
            val rightArmData = bodyData.addChild(
                "rightArm",
                ModelPartBuilder.create()
                    .uv(40, 109).cuboid(-7.0f, -6.0f, -7.0f, 13.0f, 10.0f, 14.0f)
                    .uv(94, 109).cuboid(-7.0f, 3.0f, -7.0f, 13.0f, 1.0f, 14.0f)
                    .uv(148, 109).cuboid(-3.0f, 4.0f, -3.0f, 6.0f, 8.0f, 6.0f)
                    .uv(172, 109).cuboid(-6.0f, -11.0f, -4.0f, 4.0f, 5.0f, 4.0f)
                    .uv(188, 109).cuboid(-5.0f, -14.0f, -3.0f, 2.0f, 3.0f, 2.0f)
                    .uv(196, 109).cuboid(-1.0f, -9.0f, 1.0f, 3.0f, 3.0f, 3.0f),
                ModelTransform.origin(-20.0f, -23.0f, 0.0f),
            )
            val leftArmData = bodyData.addChild(
                "leftArm",
                ModelPartBuilder.create()
                    .uv(0, 133).cuboid(-6.0f, -6.0f, -7.0f, 13.0f, 10.0f, 14.0f)
                    .uv(54, 133).cuboid(-6.0f, 3.0f, -7.0f, 13.0f, 1.0f, 14.0f)
                    .uv(108, 133).cuboid(-3.0f, 4.0f, -3.0f, 6.0f, 8.0f, 6.0f)
                    .uv(132, 133).cuboid(2.0f, -11.0f, -4.0f, 4.0f, 5.0f, 4.0f)
                    .uv(148, 133).cuboid(3.0f, -14.0f, -3.0f, 2.0f, 3.0f, 2.0f)
                    .uv(156, 133).cuboid(-2.0f, -9.0f, 1.0f, 3.0f, 3.0f, 3.0f),
                ModelTransform.origin(20.0f, -23.0f, 0.0f),
            )
            val rightForeData = rightArmData.addChild(
                "rightFore",
                ModelPartBuilder.create()
                    .uv(168, 133).cuboid(-2.0f, -1.0f, -2.0f, 4.0f, 2.0f, 4.0f)
                    .uv(184, 133).cuboid(-5.0f, 1.0f, -5.0f, 10.0f, 12.0f, 10.0f)
                    .uv(0, 157).cuboid(-6.0f, 8.0f, -6.0f, 12.0f, 2.0f, 12.0f)
                    .uv(48, 157).cuboid(-6.0f, 3.0f, -6.0f, 12.0f, 1.0f, 12.0f),
                ModelTransform.origin(0.0f, 12.0f, 0.0f),
            )
            val leftForeData = leftArmData.addChild(
                "leftFore",
                ModelPartBuilder.create()
                    .uv(96, 157).cuboid(-2.0f, -1.0f, -2.0f, 4.0f, 2.0f, 4.0f)
                    .uv(112, 157).cuboid(-5.0f, 1.0f, -5.0f, 10.0f, 12.0f, 10.0f)
                    .uv(152, 157).cuboid(-6.0f, 8.0f, -6.0f, 12.0f, 2.0f, 12.0f)
                    .uv(200, 157).cuboid(-6.0f, 3.0f, -6.0f, 12.0f, 1.0f, 12.0f),
                ModelTransform.origin(0.0f, 12.0f, 0.0f),
            )
            val rightHandData = rightForeData.addChild(
                "rightHand",
                ModelPartBuilder.create()
                    .uv(0, 179).cuboid(-2.0f, -1.0f, -2.0f, 4.0f, 2.0f, 4.0f)
                    .uv(16, 179).cuboid(-6.0f, 1.0f, -6.0f, 12.0f, 7.0f, 12.0f)
                    .uv(64, 179).cuboid(6.0f, 2.0f, -4.0f, 2.0f, 5.0f, 4.0f),
                ModelTransform.origin(0.0f, 13.0f, 0.0f),
            )
            val leftHandData = leftForeData.addChild(
                "leftHand",
                ModelPartBuilder.create()
                    .uv(76, 179).cuboid(-2.0f, -1.0f, -2.0f, 4.0f, 2.0f, 4.0f)
                    .uv(92, 179).cuboid(-6.0f, 1.0f, -6.0f, 12.0f, 7.0f, 12.0f)
                    .uv(140, 179).cuboid(-8.0f, 2.0f, -4.0f, 2.0f, 5.0f, 4.0f),
                ModelTransform.origin(0.0f, 13.0f, 0.0f),
            )
            val rightFingersData = rightHandData.addChild(
                "rightFingers",
                ModelPartBuilder.create()
                    .uv(152, 179).cuboid(-6.0f, 0.0f, -2.0f, 3.0f, 5.0f, 4.0f)
                    .uv(166, 179).cuboid(-3.0f, 0.0f, -2.0f, 3.0f, 6.0f, 4.0f)
                    .uv(180, 179).cuboid(0.0f, 0.0f, -2.0f, 3.0f, 6.0f, 4.0f)
                    .uv(194, 179).cuboid(3.0f, 0.0f, -2.0f, 3.0f, 5.0f, 4.0f),
                ModelTransform.origin(0.0f, 8.0f, -3.0f),
            )
            val leftFingersData = leftHandData.addChild(
                "leftFingers",
                ModelPartBuilder.create()
                    .uv(208, 179).cuboid(-6.0f, 0.0f, -2.0f, 3.0f, 5.0f, 4.0f)
                    .uv(222, 179).cuboid(-3.0f, 0.0f, -2.0f, 3.0f, 6.0f, 4.0f)
                    .uv(236, 179).cuboid(0.0f, 0.0f, -2.0f, 3.0f, 6.0f, 4.0f)
                    .uv(0, 198).cuboid(3.0f, 0.0f, -2.0f, 3.0f, 5.0f, 4.0f),
                ModelTransform.origin(0.0f, 8.0f, -3.0f),
            )
            val rightLegData = root.addChild(
                "rightLeg",
                ModelPartBuilder.create()
                    .uv(14, 198).cuboid(-4.0f, 0.0f, -4.0f, 8.0f, 7.0f, 8.0f)
                    .uv(46, 198).cuboid(-2.0f, 6.0f, -2.0f, 4.0f, 2.0f, 4.0f),
                ModelTransform.origin(-7.0f, 7.0f, 0.0f),
            )
            val leftLegData = root.addChild(
                "leftLeg",
                ModelPartBuilder.create()
                    .uv(62, 198).cuboid(-4.0f, 0.0f, -4.0f, 8.0f, 7.0f, 8.0f)
                    .uv(94, 198).cuboid(-2.0f, 6.0f, -2.0f, 4.0f, 2.0f, 4.0f),
                ModelTransform.origin(7.0f, 7.0f, 0.0f),
            )
            val rightShinData = rightLegData.addChild(
                "rightShin",
                ModelPartBuilder.create()
                    .uv(110, 198).cuboid(-5.0f, 0.0f, -4.0f, 10.0f, 8.0f, 9.0f)
                    .uv(148, 198).cuboid(-4.0f, -1.0f, -5.0f, 8.0f, 5.0f, 2.0f)
                    .uv(168, 198).cuboid(-6.0f, 7.0f, -7.0f, 12.0f, 3.0f, 13.0f)
                    .uv(218, 198).cuboid(-6.0f, 8.0f, -9.0f, 3.0f, 2.0f, 2.0f)
                    .uv(228, 198).cuboid(-2.0f, 8.0f, -9.0f, 4.0f, 2.0f, 2.0f)
                    .uv(240, 198).cuboid(3.0f, 8.0f, -9.0f, 3.0f, 2.0f, 2.0f),
                ModelTransform.origin(0.0f, 7.0f, 0.0f),
            )
            val leftShinData = leftLegData.addChild(
                "leftShin",
                ModelPartBuilder.create()
                    .uv(0, 215).cuboid(-5.0f, 0.0f, -4.0f, 10.0f, 8.0f, 9.0f)
                    .uv(38, 215).cuboid(-4.0f, -1.0f, -5.0f, 8.0f, 5.0f, 2.0f)
                    .uv(58, 215).cuboid(-6.0f, 7.0f, -7.0f, 12.0f, 3.0f, 13.0f)
                    .uv(108, 215).cuboid(-6.0f, 8.0f, -9.0f, 3.0f, 2.0f, 2.0f)
                    .uv(118, 215).cuboid(-2.0f, 8.0f, -9.0f, 4.0f, 2.0f, 2.0f)
                    .uv(130, 215).cuboid(3.0f, 8.0f, -9.0f, 3.0f, 2.0f, 2.0f),
                ModelTransform.origin(0.0f, 7.0f, 0.0f),
            )
            val tomeData = root.addChild(
                "tome",
                ModelPartBuilder.create()
                    .uv(140, 215).cuboid(-4.0f, -5.0f, -1.0f, 8.0f, 10.0f, 2.0f)
                    .uv(160, 215).cuboid(-3.0f, -4.0f, -2.0f, 6.0f, 8.0f, 1.0f),
                ModelTransform.origin(-18.0f, 0.0f, -6.0f),
            )
            val orbitAData = root.addChild(
                "orbitA",
                ModelPartBuilder.create()
                    .uv(174, 215).cuboid(-2.0f, -2.0f, -2.0f, 4.0f, 4.0f, 4.0f),
                ModelTransform.origin(0.0f, 0.0f, 0.0f),
            )
            val orbitBData = root.addChild(
                "orbitB",
                ModelPartBuilder.create()
                    .uv(190, 215).cuboid(-2.0f, -3.0f, -1.0f, 3.0f, 5.0f, 3.0f),
                ModelTransform.origin(0.0f, 0.0f, 0.0f),
            )
            val orbitCData = root.addChild(
                "orbitC",
                ModelPartBuilder.create()
                    .uv(202, 215).cuboid(-1.0f, -1.0f, -1.0f, 3.0f, 3.0f, 3.0f),
                ModelTransform.origin(0.0f, 0.0f, 0.0f),
            )
            return TexturedModelData.of(modelData, 256, 256)
        }
    }
}
