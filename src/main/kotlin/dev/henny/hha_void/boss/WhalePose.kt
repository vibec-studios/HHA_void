package dev.henny.hha_void.boss

/**
 * Haltungen des Leviathans Oruun. Der Server setzt sie (DataTracker, `VoidBossEntity.castPose()`),
 * das Modell spielt sie ab. `poseAge` = Ticks seit Beginn der Haltung (Client, mit tickDelta).
 * Zeiten in Ticks (20 = 1 s). Haltungen ohne Ende laufen, bis der Server die nächste setzt.
 *
 * Bewegung (Schwimmgeschwindigkeit, Neigung beim Sturzflug) liest der Renderer selbst aus der
 * Positionsänderung und dem Pitch der Entity; die Haltungen beschreiben nur Gesten.
 * Tick-genaue Choreografie jeder Haltung (für das Modell): `docs/SPEC_LEVIATHAN.md`.
 *
 * Wichtig für das Modell: Die Hitboxen ([LeviathanBody.SEGMENTS]) folgen dem Körper nur grob (gerade Achse,
 * plus [LeviathanBody.poseOffset]). Haltungen, die den Körper stark verbiegen, sollen das in der Spec
 * beschriebene Profil einhalten, sonst trifft man daneben.
 */
object WhalePose {
    /** Ruhiges Gleiten: Körper wellt sich, Fluke schlägt langsam auf und ab, Brustflossen rudern. Endlos. */
    const val SWIM = 0

    /**
     * Auftritt aus dem Sternenriss (Intro), 120 Ticks: 0-40 nur Kopf/Vorderleib sichtbar, der Körper
     * „schält“ sich hervor (darf per Skalierung/Versatz entlang der Längsachse gelöst werden), 40-90 voller
     * Körper bricht hervor und bäumt sich auf (Kopf hoch, Rücken gebogen), 90-120 Maul weit offen = Gesang.
     */
    const val EMERGE = 1

    /** Walgesang, 60 Ticks: Kopf hebt sich, Maul öffnet sich langsam, Kehlfalten pulsieren, Flossen gespreizt. */
    const val SONG = 2

    /**
     * Flukenschlag → Gravitationswelle, 46 Ticks: 0-24 Hinterleib und Fluke heben sich hoch über den Rücken
     * (Körper krümmt sich), 24-30 Fluke peitscht nach unten (Einschlag bei Tick 28), 30-46 Nachschwingen.
     */
    const val TAIL_SLAM = 3

    /**
     * Sternenatem, 90 Ticks: 0-25 Maul öffnet sich, Kopf zieht leicht zurück (Aufladen, Kehle glüht),
     * 25-80 Maul weit offen, Kopf schwenkt langsam (der Strahl kommt vom Server), 80-90 schließen.
     */
    const val BREATH = 4

    /** Sturzflug/Abtauchen: stromlinienförmig, Flossen angelegt, schnelle kräftige Flukenschläge. Endlos. */
    const val DIVE = 5

    /**
     * Gestrandet auf der Plattform (Schwachstelle offen), 140 Ticks: Körper liegt flach und schwer, Kopf
     * und Fluke zucken/schlagen gelegentlich, Brustflossen stemmen sich, das Sternenherz (Brust/Bauch) leuchtet.
     * Ab Tick 120 stemmt er sich hoch (zurück in die Luft). Die Fluke liegt flach am Boden (Rampe auf den Rücken).
     */
    const val BEACHED = 6

    /** Einsaugen (Mahlstrom), 120 Ticks: Maul weit offen, Körper bläht sich leicht auf, Kehlfalten gedehnt. */
    const val INHALE = 7

    /**
     * Kieferschnappen, 50 Ticks: 0-18 Maul reißt riesig auf, Kopf stößt vor (Ausfall), Tick 20 schnappt zu,
     * 20-50 Maul zu, Kehle/Wangen ausgebeult, leichtes Würgen/Schlucken.
     */
    const val SWALLOW = 8

    /**
     * Sternenregen beschwören, 70 Ticks: Wal rollt sich langsam um die Längsachse (bis ~90°), Flossen weit
     * gespreizt, Fluke kreist; wirkt wie ein Ritual. Danach zurückrollen.
     */
    const val DEBRIS = 9

    /**
     * Supernova (Phase 3, Ultimative), 140 Ticks: rollt sich zusammen (Kopf und Fluke zueinander, C-Form),
     * zittert immer stärker, ab 110 reißt er sich auf (gestreckt, alle Flossen gespreizt, Maul offen).
     */
    const val SUPERNOVA = 10

    /**
     * Tod, 260 Ticks: 0-50 letztes Brüllen (Kopf hoch, Maul offen), 50-90 bäumt sich senkrecht auf,
     * 90-200 rollt er sich langsam auf den Rücken, Flossen erschlaffen, Haut reißt überall auf (Licht),
     * 200-260 treibt reglos. Das Auflösen in Sterne macht der Server/FX.
     */
    const val DEATH = 11

    /** Getroffen/Taumeln, 20 Ticks: Ruck zur Seite, Kopf weg, Flossen zucken. */
    const val STAGGER = 12

    /** Noch nicht da (vor dem Intro): Modell nicht zeichnen. */
    const val HIDDEN = 13

    // ------------------------------------------------------------------ Leviathan (neu)

    /**
     * Brüllen, 60 Ticks: 0-12 Kopf hoch, Maul reißt auf, 12-48 Maul maximal offen, ganzer Körper bebt
     * (Schallwelle bei Tick 14), Brustflossen weit, Fluke steil; 48-60 schließen.
     */
    const val ROAR = 14

    /**
     * Fassrolle (Abschütteln), 60 Ticks: 0-20 Vorwarnung (Körper zittert, rollt 15° an, Flossen angelegt),
     * 20-50 volle Rolle 360° um die Längsachse (gleichmäßig, Spieler fliegen bei ~Tick 30 herunter),
     * 50-60 auspendeln.
     */
    const val BARREL_ROLL = 15

    /**
     * Rammstoß (Moby Dick), endlos: Kopf leicht gesenkt, Maul einen Spalt offen, Flossen eng angelegt,
     * maximale Schlagfrequenz, Rücken gestreckt wie ein Rammbock.
     */
    const val RAM = 16

    /**
     * Durchbruch nach oben, 70 Ticks: 0-30 senkrecht steigend (Nase oben, der Server stellt Pitch ≈ -75°),
     * Fluke peitscht, 30-50 am Scheitel: Körper bäumt sich, Maul auf, Flossen weit; 50-70 kippt nach vorn.
     */
    const val BREACH = 17

    /**
     * Blasloch-Geysir, 80 Ticks: 0-20 Rücken wölbt sich, Blasloch öffnet sich (Hautlappen auf), 20-70 Fontäne
     * (Pulsieren im 10-Tick-Takt, Ausstoß bei 22, 42, 62), 70-80 schließen.
     */
    const val BLOWHOLE = 18

    /**
     * Verschlingen (Bauch-Phase), 120 Ticks: 0-30 Kiefer reißt weiter auf als je zuvor (> 90°, Unterkiefer klappt
     * nach unten), Kehle bläht sich auf, 30-100 Maul offen, Sog, Kopf zittert, 100-110 schnappt zu, 110-120 schlucken.
     */
    const val DEVOUR = 19

    /**
     * Taumeln nach dem Ausbruch, 200 Ticks: liegt schwer auf der Seite, Flanke aufgerissen (Wunde leuchtet),
     * Atem stoßweise, Fluke zuckt; 180-200 rappelt sich auf. Schadensfenster.
     */
    const val REELING = 20

    /**
     * Rückenbeben (Abschütteln), 50 Ticks: 0-24 der Rücken zittert immer stärker (Hautwellen vom Kopf zur Fluke),
     * 24-30 Rücken schnellt hoch (Buckel), 30-50 nachbeben.
     */
    const val BACK_QUAKE = 21

    /**
     * Schwanzpeitsche über den Rücken (Abschütteln), 56 Ticks: 0-26 Schwanz krümmt sich steil hoch nach vorn
     * (Skorpion), 26-46 Fluke fegt dicht über den Rücken von hinten nach vorn, 46-56 zurück.
     */
    const val TAIL_SWEEP = 22

    /**
     * Im Bauch sind Spieler (Bauch-Phase), endlos: Wal schwebt träge, Bauch aufgebläht und glühend,
     * gelegentliches Krampfen (Server setzt bei Kerntreffern kurz [STAGGER]).
     */
    const val GORGED = 23

    /**
     * Ausbruch, 60 Ticks: 0-20 Körper krampft, Flanke wölbt sich nach außen, Tick 22 reißt die Flanke auf
     * (Licht + Spieler fliegen heraus), 22-60 sackt zusammen und kippt auf die Seite (geht in [REELING] über).
     */
    const val BREAKOUT = 24

    /**
     * Flutwelle beschwören, 70 Ticks: 0-30 Wal stellt sich quer, Fluke hebt sich, 30-36 Fluke schlägt
     * flach durch die Luft (die Welle löst sich), 36-70 Nachschwingen, Kopf schaut der Welle nach.
     */
    const val FLOOD = 25

    /** Anzahl Haltungen (für Tests/Befehle). */
    const val COUNT = 26

    /** Dauer einer Haltung in Ticks; 0 = endlos. */
    fun duration(pose: Int): Int = when (pose) {
        EMERGE -> 120
        SONG -> 60
        TAIL_SLAM -> 46
        BREATH -> 90
        BEACHED -> 140
        INHALE -> 120
        SWALLOW -> 50
        DEBRIS -> 70
        SUPERNOVA -> 140
        DEATH -> 260
        STAGGER -> 20
        ROAR -> 60
        BARREL_ROLL -> 60
        BREACH -> 70
        BLOWHOLE -> 80
        DEVOUR -> 120
        REELING -> 200
        BACK_QUAKE -> 50
        TAIL_SWEEP -> 56
        BREAKOUT -> 60
        FLOOD -> 70
        else -> 0
    }

    /** Name für Logs/Tests. */
    fun name(pose: Int): String = when (pose) {
        SWIM -> "SWIM"; EMERGE -> "EMERGE"; SONG -> "SONG"; TAIL_SLAM -> "TAIL_SLAM"; BREATH -> "BREATH"
        DIVE -> "DIVE"; BEACHED -> "BEACHED"; INHALE -> "INHALE"; SWALLOW -> "SWALLOW"; DEBRIS -> "DEBRIS"
        SUPERNOVA -> "SUPERNOVA"; DEATH -> "DEATH"; STAGGER -> "STAGGER"; HIDDEN -> "HIDDEN"; ROAR -> "ROAR"
        BARREL_ROLL -> "BARREL_ROLL"; RAM -> "RAM"; BREACH -> "BREACH"; BLOWHOLE -> "BLOWHOLE"; DEVOUR -> "DEVOUR"
        REELING -> "REELING"; BACK_QUAKE -> "BACK_QUAKE"; TAIL_SWEEP -> "TAIL_SWEEP"; GORGED -> "GORGED"
        BREAKOUT -> "BREAKOUT"; FLOOD -> "FLOOD"
        else -> "?$pose"
    }
}
