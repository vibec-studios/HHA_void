# Arena „Die Sternentiefe“ – Arbeitsnotizen

Code: `src/main/kotlin/dev/henny/hha_void/boss/VoidTribunal.kt` (`TribunalBuilder`, Struktur `VoidTribunalStructure`,
Piece `VoidTribunalPiece` – Namen bleiben wegen Registrierung). Rundflug-Test: `HHA_VOID_TEST=arena ./gradlew runClientGameTest`
(`src/gametest/.../ArenaShowcase.kt`, Bilder in `build/run/clientGameTest/screenshots`).

## Stand 14.11.2026 – v4 „Ø 372" mit Außenplattformen (Mats: „Durchmesser 350–400, Außenplattformen mehr nutzen")

| Konstante | Wert | Bedeutung |
|---|---|---|
| `RADIUS` / `FLOOR_RADIUS` | 186 / 176 | Plattform Ø ≈ 372 / freier Boden |
| `CEILING` | 72 | Luftraum über der Plattform frei (`/voidboss place` räumt r ≤ 198) |
| `EXTENT` | 282 | Ausdehnung inkl. Inselring (≈ 35×35 Chunks) |
| Monolithen | 12 bei r 62 (`MONOLITHS`), 18 bei r 132 | Deckung (Supernova, Mahlstrom, Sternenatem) |
| Bastionen | 3 bei 0/120/240°, y 7 | erhöhte Deckung am Rand |
| Ruinen | 16 bei r 102–108, 6 bei r 38 | Deckung in Brusthöhe |
| Walrippen | 3 Bögen bei 60/180/300° über dem Rand | Deko |
| Außenplattformen | 6 (r ≈ 225–245, y 9/13/17), je **Harpunen-Geschütz**, Deckungsmauer, Leuchten | über Sprungpfade (Stufe +1, 6,3 Blöcke) aus Toren bei 30°+k·60° |
| Trümmerinseln | 72 im Ring r 196–270, y 3–49, 4 Leuchtfeuer | 4 kleine äußere stürzen in Phase II ab |

- **Sparsam statt Raster:** `TribunalBuilder.region(x0, z0)` erzeugt nur einen 16×16-Ausschnitt (35 KB); Inseln, Rippen,
  Sternbilder und Kristalle sind kleine vorberechnete Listen. Speicher nach dem Bau ≈ normal (Test: Heap ~0,7 GB im Client).
- **`/voidboss place`:** Chunk für Chunk von innen nach außen, Chunks werden vorab **asynchron** per Ticket geladen,
  geschrieben wird nur in geladene (≤ 14 ms Bauzeit je Tick). Danach Aufzug.
- **Natürlich:** Haupt-Struktur `void_tribunal` (Set `void_tribunals`, spacing 72 / separation 44 – Arenen ≥ 700 Blöcke
  auseinander) erzeugt im Weltlauf nur die Chunks mit Referenz (8 Chunks um den Start). Alle übrigen Chunks legt die
  **Kachel-Struktur** `void_tribunal_tile` (Set `void_tribunal_tiles`, spacing 1): sie rechnet für jeden Chunk die
  Startpunkte der Haupt-Struktur nach (`TribunalBuilder.siteAt`, gleiche Prüfung Boden ≥ 45 + Biom) und erzeugt genau diesen
  Chunk. `/place structure hha_void:void_tribunal` baut die ganze Arena aus der Haupt-Struktur.
- **Keine Endermen:** `spawn_overrides` (monster leer) in beiden Struktur-JSONs, `ArenaRegistry` (gebaute + vom Altar
  gemeldete Arenen) + `SpawnHelperMixin`, hineinteleportierte Endermen entfernt `ArenaGuard` alle 2 s.

## Stand 13.11.2026 – v3 Himmelsarena für den Leviathan

Mats: „Arena gerne so groß wie du willst (kann auch 300 Blöcke breit sein), darf auch in der Luft sein.“

| Konstante | Wert | Bedeutung |
|---|---|---|
| `FLOOR_Y` | 176 | Bodenhöhe der natürlich erzeugten Arena (über den End-Außeninseln) |
| `RADIUS` / `FLOOR_RADIUS` | 74 / 66 | Plattform / freier Boden |
| `CEILING` | 72 | Luftraum, den `/voidboss place` räumt (Radius 98) |
| Korridor | r 66–96, y 21–58 | Flugraum des Wals, frei von Inseln (Test zählt Blöcke) |
| Monolithen | 12 × 5×5, r 38, bis y 17 | Deckung (Supernova, Mahlstrom-Sog) |
| Bastionen | 4 bei 100/150/280/330°, y 7 | zweite Ebene mit Rampe, Leuchtfeuer |
| Ruinen | 8 Mauern r 50–54, bis y 5 | Deckung in Brusthöhe |
| Inseln | 46 + 2 Sprungpfade, r bis ≈ 122, y bis 45 | dritte Ebene; 4 kleine stürzen in Phase II ab |
| Aufzug | Säule bei (0, 16) | `hha_void:star_lift` von der Insel darunter bis als Lichtloch in den Boden |

- Ausdehnung ±118 × ±122 Blöcke (innerhalb der 8-Chunk-Grenze für Strukturen), y −64..+53; ≈ 380 000 Blöcke.
- Raster mit Palette (1 Byte je Zelle, ≈ 9 MB, einmal ≈ 160 ms). `/voidboss place` baut über ≈ 30 Ticks
  (90 000 Blöcke je Tick geprüft), danach Luftraum, dann Aufzug.
- Struktur-Abstand im End: `spacing 48 / separation 24`.
- Bauch-Innenraum wird erst im Kampf 84 Blöcke unter der Mitte gebaut (`LeviathanBelly`).

## Stand 12.11.2026 – v2 „groß“ (8–10 Spieler)

Mats: „Arena deutlich größer – 8–10 Spieler in voller Netherit-Ausrüstung mit Maces.“ Alle Maße neu parametrisiert
(benannte Konstanten oben in `TribunalBuilder`), nicht hochskaliert.

| Konstante | Wert | Bedeutung |
|---|---|---|
| `RADIUS` | 50 | Hauptplattform bis Außenkante Brüstung |
| `FLOOR_RADIUS` | 44 | flacher, freier Boden (y = 0); 44–50 ist eine Stufe (y = 1) mit Brüstung |
| `CEILING` | 52 | Luftraum über dem Boden frei bis hier (Radius ≤ 58,5) |
| `MONOLITHS` | 10 Stück, Radius 26, Winkel 18° + k·36° | 5×5, voll bis y 11, Kopf bis y 14 |

- Flugkorridor Radius 36–56, Höhe 12–30 ist frei (Test zählt feste Blöcke darin: soll 0 sein).
  Rippenbögen (30°/210°) steigen außen bis Scheitel ≈ y 11 auf und enden über dem äußeren Band (r ≈ 43, y ≈ 9).
- Trümmerinseln Radius ~53–81: unter Höhe 10 ab Radius 53, darüber erst außerhalb des Korridors (Rand ≥ 57).
  Zwei Sprungpfade (Start 62° und 242°, Lücke in der Brüstung mit Torpfosten) mit Stufen +1, Lücke ≈ 1 Block,
  enden auf einer Leuchtfeuer-Insel (Höhe 20); drei weitere Leuchtfeuer auf den höchsten großen Inseln.
- Boden: dunkles Sternenfeld (Tiefenschiefer) bis r 33,5 mit Ringen (r 10,5 Weinender Obsidian, r 18,3 und r 26
  gepunktet), Achsen zu den Monolithen, 5 Sternbilder (im Osten der Wal), Horizontring Purpur, Ziegelring,
  äußeres Band mit 18 Feldern und Lichtern. Sockel r 6,6 mit Altar (0,1,0), zehn Endstäbe.
- Struktur-Abstand im End: `spacing 40 / separation 16` (vorher 28/10), weil die Arena jetzt ~165 Blöcke breit ist.
- Leistung: Raster einmal berechnet (lazy), Chunk-Erzeugung liest nur die eigenen Spalten; `/voidboss place`
  setzt Luft nur, wo etwas im Weg ist.

## Stand 11.11.2026 – v1

R 34 / Boden 29 / Decke 40, 6 Monolithen 3×3 in r 17; `/voidboss place` ≈ 270 ms, Raster ≈ 90 ms.
