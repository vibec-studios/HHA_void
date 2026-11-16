# Sternenwal-Modell – Fortschrittsnotiz

## Aufbau
- `tools/textures/GenWhale.java` = einzige Quelle für Geometrie, UVs und Texturen (Wal + Sternling).
  Schreibt `client/boss/WhaleModelData.kt`, `client/boss/StarlingModelData.kt` und die PNGs
  (`void_boss.png`, `void_boss_glow.png`, `void_boss_p3.png`, `void_boss_p3_glow.png`, `void_debtor.png`, `void_debtor_glow.png`).
  Aufruf: `java tools/textures/GenWhale.java . [--preview <ordner>]` (Vorschau = Software-Rasterer, Sekunden statt Spielstart).
- Einheiten Wal: 8 Einheiten = 1 Block, Renderer-Skalierung 2.0, Textur 2 Texel je Einheit (16 px/Block, 1024x1024).
  Körpermitte = Modell-Ursprung (0,0,0) = 3 Blöcke über der Entity-Position. Front = -Z, +Y unten, links = +X.
- `client/boss/VoidBossModel.kt`: `WhaleModel` (Rig je Haltung als FloatArray, Überblendung, Dauerwelle) + `StarlingModel`.
- `client/boss/VoidBossClient.kt`: `WhaleRenderer`, `StarlingRenderer`, RenderStates, Glow-Feature, Registrierung.
- `src/gametest/.../WhaleShowcase.kt`: alle Haltungen, per `/tick freeze` angehalten, mehrere Winkel, Tag/Nacht, Phase 1/3,
  gestrandet auf Plattform, Sternlinge. Teilmengen: Umgebungsvariable `HHA_VOID_WHALE=day,poses,beached,p3,night,starlings,flight`
  (`flight` = Flugstrecke mit Steigen/Sinken/Kurven und echte Kreisbahn, Bildfolge alle 5 Ticks; siehe `WHALE_ANIM_NOTES.md`).

## Fertig (Stand Abgabe)
- Generator: Geometrie Wal (183 Quader, Kopf in 4 Stufen verjüngt, Herz als Kristall) + Sternling (Manta), UV-Packer,
  Texturen Phase 1-2 und Phase 3 (Risse mit Galaxie), Glow, Vorschau.
- `WhaleModel`: alle 14 Haltungen aus `WhalePose`, Überblendung, Welle/Flossen/Kurvenlage/Herzschlag/Treffer-Ruck.
- Renderer: Skalierung 2.0, Mindestlicht 11, kein Umfallen/keine Rottönung im Tod, vergrößerte Cull-Box, HIDDEN zeichnet nichts.
- Sternling: Modell, Textur + Glow, Renderer (Skalierung 0.8) registriert, Sturzhaltung bei `isCharging`.
- Im Spiel geprüft: `HHA_VOID_TEST=whale` (4 Durchläufe, 64 Bilder) und `HHA_VOID_TEST=fight` (ganzer Kampf inkl. Intro,
  Strandung, Phasen-Szenen, Tod) – läuft ohne Fehler.
- `object Poses` aus `VoidBossEntity` gelöscht, alte `void_boss_reborn*.png` gelöscht.
- Konstanten: BODY_UP 3.0, MOUTH_FORWARD 11.0, HEART_FORWARD 1.25 (war 4.0), FLUKE_BACK 11.5.
- `./gradlew build --no-daemon` grün.

## Offen / Ideen
- Gestrandet rollt das Modell ~49° auf die linke Seite; das Herz liegt dann ~2,3 Blöcke rechts neben `heartPos()`
  (Partikel des Servers sitzen also leicht daneben).
- Körper leuchtet nachts nur dezent (Sterne, Sternbilder, Augen, Herz, Kehle); mehr Eigenleuchten = Alpha in `whalePaint` (glow) erhöhen.
- Kein Schatten (Radius 0). Nebelfetzen als eigene Teile gibt es nicht; Sternbild-Linien sind Teil der Glow-Textur.
- Treffer-Ruck (`flinch`) nur im Kampf-Durchlauf gesehen, nicht einzeln fotografiert. Kurvenlage/Flugbahn: `WHALE_ANIM_NOTES.md`.
