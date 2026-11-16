<img src="assets/icon.svg" width="64" height="64" alt="">

# HHA_void

Void-Addon für **H&H Armors (HHA)** – Fabric, Minecraft 1.21.11, baut gegen `libs/HHA-0.8.0.jar`.

## Kampf-Überarbeitung nach Mats' Spieltest (14.11.2026)

- **Hitboxen = Modell**: 38 kleine Volumen an den echten Modellknochen (Kopf, Kiefer, Hals, Rumpf, Schwanz, Fluke-Lappen,
  Flossenglieder, Herz, Blasloch), gerechnet per Vorwärtskinematik aus derselben Haltung wie das Modell
  (`boss/LeviathanRig.kt` = Kopie von `VoidBossModel.rig`) – auch geneigt, gerollt, gebogen, mit offenem Kiefer.
  Der Renderer nimmt Neigung/Biegung/Schräglage aus derselben Rechnung (`LeviathanBody.Motion`). F3+B zeigt sie.
- **Draufstehen**: Reiter stehen auf der glatten Rückenfläche (nicht auf Boxkanten), werden mit jeder Lageänderung exakt
  mitgenommen und melden dem Server „am Boden"; abgeworfen wird nur absichtlich (Fassrolle, Rückenbeben, Schwanzpeitsche,
  Abtauchen, Aufstemmen).
- **Neue/umgebaute Angriffe**: Sternenatem (sichtbares Aufladen, dicker Strahl vom Maul, fegt über eine angezeigte Bahn,
  Deckung schützt, Brandspur zerspringt in Sternensplitter), Rammstoß (Bahn sofort sichtbar, 0,6 s Lauern, 3,3 Blöcke/Tick),
  Durchbruch als Wal-Breach (taucht durch den Boden, bricht unter dir durch, Bogen über die Arena, kracht 70 Blöcke weiter
  wieder durch), Kieferschnappen (Maul landet per Vorausberechnung am Ziel), Leere-Eruption (Sektoren), Tiefflug,
  Festgenagelt (Harpunen).
- **Phase III angreifbar**: niedrigere Kreisbahn, nach Angriffen oft Tiefflug („erschöpft"), nach der Supernova ausgebrannt
  am Boden, jeder zweite Rammstoß strandet, Harpunen nageln ihn fest.
- **Bauch**: fünf Abschnitte – Schlund (Würgknoten) → Magen (Kerne, Säure) → Gedärme (Ganglien, Peristaltik, Säuretümpel)
  → Lunge (Wendelrampe, Atemzüge, Lungenbläschen) → Herzkammer (Klappen schützen das Sternenherz, Herzschlag-Wellen).
- **Arena Ø ≈ 372** (r 186), sechs Außenplattformen mit **Harpunen-Geschützen** (Moby Dick), Inselring bis r ≈ 270,
  chunkweise Erzeugung (keine großen Raster), natürliche Arenen über Haupt-Struktur + Kachel-Struktur.
- **Keine Endermen** auf der Arena (Struktur-`spawn_overrides`, Laufzeit-Sperre per Mixin, hineinteleportierte verschwinden).

## Leviathan-Umbau (13.11.2026)

Oruun ist jetzt ein **≈ 58 Blöcke langer Leviathan** – Spec für Modell/Animation: [`docs/SPEC_LEVIATHAN.md`](docs/SPEC_LEVIATHAN.md),
Arbeitsstand: [`docs/LEVIATHAN_PROGRESS.md`](docs/LEVIATHAN_PROGRESS.md).

- **Himmelsarena** (Boden y 176): Stand 13.11. – inzwischen Ø ≈ 372, siehe oben und `docs/ARENA_NOTES.md`. Damals: Plattform r 74, 12 Monolithen, Ruinenmauern, 4 Bastionen (y 7),
  Inselring bis r ≈ 122 mit Sprungpfaden, Sternenaufzug von der Insel darunter. Siehe `docs/ARENA_NOTES.md`.
- **Segment-Hitboxen** (16 Boxen entlang des Körpers): Treffer zählen, wo man ihn sieht; Blasloch ×1,5, Herz offen ×2.
  **Man kann auf ihm stehen** – auch im Flug (Client nimmt den Spieler mit); dann versucht er, einen abzuschütteln.
- **Moveset**: Flukenschlag, Sternenatem, Sternenregen, Rammstoß (Moby Dick, mit Bruchlandung), Mahlstrom, Durchbruch von
  unten, Kieferschnappen, Sternlinge, Supernova, Blasloch-Geysir, Flutwelle mit Lücken, Fassrolle, Rückenbeben,
  Schwanzpeitsche, Abtauchen.
- **Phasen**: Intro → I → „Die Tiefe erwacht“ (Boden reißt, Inseln stürzen, Durchbruch) → II → **Verschlingen** →
  **Bauch-Phase** (Kerne zerstören, Säure steigt, Parasiten) → **Ausbruch** (er taumelt) → III Raserei → Tod.

## Was in der Runde davor geändert wurde

Vorgabe: **keine Fähigkeiten ändern** – nur Modelle, Texturen und der Boss.

- **Neuer Boss: Oruun, der Sternenwal** (ersetzt Vael). Design: [`docs/SPEC_WHALE.md`](docs/SPEC_WHALE.md).
  - Fliegt über der Arena, drei Phasen (Gesang / Tiefe / Supernova), Angriffe: Gravitationswelle,
    Sternenatem, Trümmerregen, Sturzflug mit Strandung (Herz offen = ×1,6 Schaden), Schwarzes Loch,
    Schwerelosigkeit, Verschlingen, Sternlinge (Diener), Supernova (Deckung hinter Monolithen).
  - Wer von der Plattform fällt, wird zurückgeholt statt zu sterben; Beute fällt in die Arenamitte.
  - Cutscenes (Intro, Phase II/III, Tod mit Sternbild), Dialoge DE/EN, Buch „Das Lied des Sternenwals“.
- **Arena „Die Sternentiefe“** (gleiche Struktur-ID, End-Außeninseln): Sternenkarte im Boden, 6 Monolithen,
  Walrippen-Bögen, schwebende Trümmerinseln.
- **Musik & Sounds**, komplett synthetisiert: `tools/sounds/GenWhaleAudio.java` (braucht portable ffmpeg).
- Rüstung/Waffen/Altar im Stil der Blood-Rüstung und das Wal-Modell: siehe `tools/textures/`.

IDs (`void_boss`, `void_debtor`, `void_altar`, `void_tribunal`) sind gleich geblieben.

## Bauen und testen

```
./gradlew build                                   # Jar unter build/libs/
HHA_VOID_TEST=fight ./gradlew runClientGameTest   # ganzer Kampf mit Screenshots
HHA_VOID_TEST=whale|armor|arena ...               # Modell-/Rüstungs-/Arena-Vitrine
```

Screenshots: `build/run/clientGameTest/screenshots`.

Befehle (OP): `/voidboss place` (Arena bauen, verteilt über Ticks, ~15–25 s), `/voidboss spawn`, `/voidboss remove`,
`/voidboss attack <0-18>` (Angriff erzwingen), `/voidboss phase <2|3|4|5>` (2 = Tiefe erwacht, 3 = Verschlingen,
4 = Bauch bis zur Herzkammer überspringen, 5 = Ziele des aktuellen Bauch-Abschnitts zerstören),
`/voidboss pose <n> [phase]` (Haltung für Modelltests).

Angriffsnummern: 0 Flukenschlag · 1 Sternenatem · 2 Sternenregen · 3 Rammstoß · 4 Mahlstrom · 5 Durchbruch ·
6 Kieferschnappen · 7 Sternlinge · 8 Supernova · 9 Blasloch-Geysir · 10 Flutwelle · 11 Fassrolle · 12 Rückenbeben ·
13 Schwanzpeitsche · 14 Abtauchen · 15 Verschlingen · 16 Leere-Eruption · 17 Festgenagelt (Harpunen) · 18 Tiefflug.

Tests: `HHA_VOID_TEST=fight` (Teilmengen `HHA_VOID_FIGHT=hitbox,attacks,ride,harpoon,phase2,belly,p3,death,robust`), `arena`, `whale`
(`HHA_VOID_WHALE=leviathan` für die neuen Haltungen).
