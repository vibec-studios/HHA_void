# HHA Void – Neuer Boss: Oruun, der Sternenwal

Stand 11.11.2026. Ersetzt Vael (den „Letzten Gläubiger“) komplett. Vorgabe des Void-Autors:
**keine Fähigkeiten der Rüstung/Waffen ändern**, nur Modelle, Texturen und den Boss.
Mats' Wahl aus den Boss-Ideen: **„16. Astral Whale – Der Sternenwal“** – ein riesiger Wal, der
schwerelos durch den Himmel gleitet; erzeugt Gravitationswellen, schwarze Löcher und fliegende Trümmerfelder.

## Lore (kurz)

Oruun ist älter als die Sterne. Er schwimmt durch das Nichts zwischen ihnen und frisst das Licht
erloschener Sonnen. Was er zurücklässt, ist Leere – aus diesen Resten wird das Void-Erz/die Void-Rüstung.
Wer Void trägt, trägt ein Stück seiner Stille; sein Gesang ruft sie zurück. Er spricht nicht wie ein Mensch:
seine „Worte“ sind Gesang, im Spiel als kursive, sternenfarbene Zeilen (Gedankenstimme).
Ton: uralt, ruhig, fast traurig, nie albern. Nach dem Sieg zerfällt er in ein neues Sternbild.

## IDs (bleiben, damit vorhandene Welten/Strukturen weiter funktionieren)

- Entity `hha_void:void_boss` (Klasse `VoidBossEntity`) = Oruun. Name: „Oruun, der Sternenwal“.
- Entity `hha_void:void_debtor` (Klasse `VoidDebtorEntity`) = **Sternling** (kleiner leuchtender Sternrochen, Diener).
- Block `hha_void:void_altar` = **Ruf der Tiefe** (Beschwörungsstein in der Mitte der Arena).
- Struktur `hha_void:void_tribunal` = Arena **Die Sternentiefe** (End, äußere Inseln, wie bisher).
- Haltungen: `boss/WhalePose.kt` (Vertrag Server ↔ Modell).

## Größe und Bewegung

- Modell ca. **24 Blöcke lang**, ~7 hoch (ohne Flossen), Brustflossen-Spannweite ~14.
- Hitbox `dimensions(12f, 6f)` (AABB, Körpermitte). Kopf und Fluke ragen sichtbar über die Box hinaus.
  Entity-Position = Unterkante der Box; **Körpermitte ≈ 3 Blöcke über der Entity-Position**.
- Fliegt (NoGravity + noClip, Geschwindigkeitssteuerung). Kreist in **Radius 26–34**, **Höhe 12–24** über dem
  Arenaboden; Sturzflüge queren die Mitte; gestrandet liegt er auf der Plattform (Radius < 20).
- Neigung (Pitch) der Entity = Neigung des Körpers (Sturzflug nach unten, Aufstieg nach oben); der Renderer
  dreht das Modell um die Körpermitte.

## Arena „Die Sternentiefe“

- Runde Hauptplattform (Radius ~34) hoch über der End-Leere, Boden flach bis Radius 24 (keine hohen Hindernisse,
  der Wal strandet dort). Mitte: Ruf der Tiefe (Altar) auf `(0,1,0)`.
- **6 Monolithen** (Deckung, 3×3, ~9 hoch) in Radius ~17: Schutz vor der Supernova (Sichtlinie).
- Ring aus **schwebenden Trümmerinseln** (Radius 38–55, Höhe 3–30), teils als Plattform erreichbar.
- Material: Endstein, Purpur, Obsidian, Weinender Obsidian, Amethyst, Schwarzstein, Tiefenschiefer, getöntes Glas,
  Endstäbe, Laternen. Stimmung: kosmisches Observatorium/Walknochen-Fragmente, „Sternenkarte“ im Boden.
- Fallen in die Leere: Wer unter Boden −20 fällt, wird von Oruuns Schwerkraft zurück auf die Plattform geholt
  (6 Schaden, kurz Langsamkeit) statt zu sterben.

## Phasen (600 HP, Rüstung 12)

| Phase | HP | Titel | Inhalt |
|---|---|---|---|
| I | 100–65 % | **I — DER GESANG** | Gravitationswelle, Sternenatem, Trümmerregen, Sturzflug → Strandung |
| II | 65–30 % | **II — DIE TIEFE** | + Schwarzes Loch, Schwerelosigkeit, Verschlingen, Sternlinge |
| III | < 30 % | **III — SUPERNOVA** | aufgerissene Haut (Galaxie leuchtet durch), schneller, Ereignishorizont + Supernova |

### Angriffe

- **Gravitationswelle** (`TAIL_SLAM`): Fluke schlägt auf die Plattform (Wal kommt dafür tief), Ring breitet sich
  aus (Shockwave), wer am Boden steht, wird getroffen + hochgeschleudert → springen. Phase III: 2 Ringe.
- **Sternenatem** (`BREATH`): Strahl aus Sternenlicht aus dem Maul, fegt langsam über die Plattform.
- **Trümmerregen** (`DEBRIS`): Felsbrocken (Block-Displays) schweben aus der Leere hoch, kreisen und stürzen auf
  markierte Stellen (Schatten-/Partikelkreis 1,5 s vorher).
- **Sturzflug** (`DIVE`): stürzt quer über die Plattform (Schaden + Rückstoß auf der Bahn); in Phase I/II endet
  jeder zweite Sturzflug in einer **Strandung** (`BEACHED`, 6 s): Herz offen = ×1,6 Schaden, Nahkampf-Fenster.
- **Schwarzes Loch** (II+, `INHALE`): Singularität über der Plattform zieht Spieler an, Schaden im Kern.
- **Schwerelosigkeit** (II+, `SONG`): alle Spieler bekommen kurz Schwebe, Trümmer kreisen; danach fallen.
- **Verschlingen** (II+, `SWALLOW`): Ausfall auf einen Spieler; erwischt = im Wal gefangen (unsichtbar, kein
  Bewegen, Schaden über Zeit), frei nach 4 s oder sofort, wenn die anderen dem Wal 60 Schaden machen; wird
  ausgespuckt. Allein: Ausspucken nach 3 s.
- **Sternlinge** (II+): 3–5 kleine fliegende Rochen (Vex-artig), 16 HP.
- **Supernova** (III, `SUPERNOVA`, alle ~50 s): lädt 7 s, dann Explosion – nur wer hinter einem Monolithen steht
  (keine Sichtlinie zum Herzen), bleibt verschont; sonst 26 Schaden + Rückstoß.

### Cutscenes (Kamera wie bisher `VoidCutscene`, Leertaste halten = überspringen)

1. **Intro** (~9 s): Spieler nähert sich dem Ruf der Tiefe → Sternenhimmel reißt auf, Gesang aus der Ferne,
   Oruun bricht aus dem Riss (`EMERGE`), zieht einen Kreis um die Plattform, singt (`SONG`) → Titelkarte.
2. **Phase II** (~4 s): taucht in die Leere, kommt von unten wieder hoch, `SONG`, Titelkarte.
3. **Phase III** (~5 s): Haut reißt auf, Licht bricht durch, `SUPERNOVA`-Zittern, Titelkarte.
4. **Tod** (~8 s): letzter Gesang (`DEATH`), rollt auf den Rücken, zerfällt in Sterne, die ein Sternbild am
   Himmel bilden; Titel „DAS LIED IST VERKLUNGEN“.

### Musik und Klang (synthetisiert, eigener Generator)

- `music.whale_phase1` (ruhig, weit, tiefe Flächen, Walgesang-Motiv), `music.whale_phase2` (Puls, Spannung),
  `music.whale_phase3` (Höhepunkt), `music.whale_victory` (Auflösung), `ambient.deep` (Arena-Ambiente).
- Effekte: `whale.song` (3 Varianten), `whale.groan`, `whale.hurt`, `whale.death`, `gravity_wave`, `black_hole`,
  `debris`, `star_breath`, `swallow`, `supernova_charge`, `supernova_blast`, `starling`.

### Belohnung

Wie bisher 8–14 Void-Barren + Netherstern, dazu das Buch **„Das Lied des Sternenwals“** (ersetzt das Hauptbuch).
