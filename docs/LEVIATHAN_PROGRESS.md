# Leviathan-Umbau – Fortschritt

Auftrag: Oruun zum Leviathan ausbauen (Größe ~58 Blöcke, große Himmelsarena, segmentierte Hitboxen + Draufstehen,
neues Moveset, krasse Phasenübergänge, Bauch-Phase). Modelle/Texturen/Animationen macht später Fable
(Schnittstelle: `boss/WhalePose.kt`, `docs/SPEC_LEVIATHAN.md`).

## Stand 13.11.2026 (fertig bis auf Fable-Teil)
- [x] `boss/LeviathanBody.kt` – Maße (×2,4, Renderer-Skalierung 4,8), 16 Hitbox-Segmente, Gestrandet-Treppenprofil
- [x] `boss/LeviathanPartEntity.kt` – Segment-Entity (LivingEntity, nur Client-seitig kollidierbar, leitet Schaden weiter)
- [x] `client/boss/LeviathanClient.kt` – Segmente je Client-Tick setzen, eigenen Spieler mitnehmen
- [x] `boss/LeviathanCoreEntity.kt`, `boss/LeviathanBlocks.kt`, `boss/LeviathanBelly.kt` – Bauch-Phase
- [x] `boss/VoidTribunal.kt` – Arena v3 (R 74, Inseln bis r ≈ 122, Bastionen, Ruinen, Aufzug, Bau über Ticks)
- [x] `boss/VoidBossEntity.kt` – komplett neu: 16 Angriffe, Abschütteln, Szenen, Bauch-Phase
- [x] Client: Cutscenes (Phase II, Verschlingen, Bauch, Ausbruch, Tod), FX (Zone/Welle/Bahn/Sternfall/Bildschirm), Kerne
- [x] `docs/SPEC_LEVIATHAN.md`, README, ARENA_NOTES

## Tests (alle `./gradlew runClientGameTest`)
- `arena`: `/voidboss place` 72–75 Ticks (30 000 Blöcke/Tick), 380 165 Blöcke; natürliche Erzeugung per
  `/place structure` an einer Inselstelle (3500, −760): Altar auf y 176, Aufzug von der Insel ✓.
- `fight` (voll): Intro, alle 16 Angriffe, Draufstehen gestrandet + im Flug (Wal wählt selbst die Fassrolle und wirft ab),
  Rückenbeben/Schwanzpeitsche/Abtauchen, Phase II, Verschlingen, Bauch (Kerne sichtbar, Säure steigt nach 30 s),
  Ausbruch, Raserei, Supernova, Sturz in die Leere, Tod + Beute, 0 verbleibende Segmente, 0 Exceptions.
- `fight` Teil `robust`: Wal ohne Arena, Verschlingen, Wal wird in der Bauch-Phase entfernt → Spieler lebt, ist frei,
  Marke weg, Innenraum abgebaut.

## Behobene Funde aus den Läufen
- Cutscenes brachen ab, wenn der Wal beim Client (noch) nicht getrackt war → laufen mit Anker weiter.
- Server warf Reiter per Kontaktschaden ab (Server-Lage ≠ Client-Lage) → Lage-Historie, Server-Boxen 3 Ticks verzögert.
- Schrittweiser Arenabau konnte bei Tick-Grenze an Spalte x = 0 von vorn beginnen (Endlosbau) → behoben.
- Belly-Marke: Zeitvergleich Server-Ticks vs. Weltzeit → vereinheitlicht; Rückkehrpunkt nicht mehr auf dem Altar.

## Wenn du hier weitermachst
- Angriffsnummern: `VoidBossEntity.ATTACK_NAMES` (0 Flukenschlag … 15 Verschlingen), `/voidboss phase 2|3|4`.
- Bauch: unter der Arena (`LeviathanBelly.DEPTH` = 84), Marke `hha_void.belly` an Spielern (Logout/Neustart).
- Testlog: `build/run/clientGameTest/logs/latest.log`, Zeilen `[fight]` (Zustand jede Sekunde).

## Runde 3 – Mats' Spielfeedback (14.11.2026, Kampf-Agent)

Plan (Punkte 1–10 aus dem Feedback). Status wird hier laufend abgehakt.

- [x] 1 Hitboxen: `boss/LeviathanRig.kt` (Kopie der Posen-Rigs aus `VoidBossModel.rig`, gemeinsam für Server+Client) +
      Vorwärtskinematik der Modellknochen in `LeviathanBody` → ~38 kleine Boxen an den echten Knochen (Kopf/Kiefer/
      Hals/Brust/Bauch/Hüfte/Schwanz/Fluke/Flossen/Herz/Blasloch), Pitch/Rolle/Kurven/Posen. Renderer nimmt Lage/
      Biegung aus derselben deterministischen Funktion (`LeviathanBody.Motion`), damit Bild = Boxen.
- [x] 2 Draufstehen: Reiter kollidieren nicht mit den Boxen, sondern stehen auf der glatten Rückenfläche
      (`LeviathanBody.surface`), Mitnehmen über Knochen-Frames, Abwurf per Paket `RIDE_RELEASE`.
- [x] 3 Sternenatem neu (Aufladen, dicker Strahl vom Maul, fegt über die Spieler, Brandspur, Splitter brechen auf).
- [x] 4 Rammstoß: kurze Vorwarnung, schneller Stoß, Treffer entlang der Boxen.
- [x] 5 Durchbruch als echter Wal-Breach (Bogen über die Arena, Drehung, Einschlag an anderer Stelle, zweites Loch).
- [x] 6 Kieferschnappen trifft (Maul aus FK, Lag-Ausgleich, Zone um den Kiefer bei Tick 20).
- [x] 7 Phase III angreifbar (tiefer, Nahkampf-Fenster, Erschöpfung nach Angriffen).
- [x] 8 Bauch: mehrere Abschnitte (Schlund → Magen → Gedärme → Lunge → Herzkammer), Aufgaben, Wächter.
- [x] 9 Keine Endermen auf der Arena (Struktur `spawn_overrides` + Laufzeit-Mixin für `/voidboss place`).
- [x] 10 Arena Ø ≈ 372 (r 186), Außenplattformen mit Harpunen-Geschützen, sparsame Erzeugung (chunkweise),
       Kachel-Struktur für Chunks außerhalb der 8-Chunk-Grenze.

### Stand Runde 3 (Code geschrieben, kompiliert – Tests laufen)
Neu/umgebaut: `boss/LeviathanRig.kt` (Rig-Kopie), `boss/LeviathanBody.kt` (FK, 38 Volumen, Rückenfläche, Motion),
`boss/LeviathanPartEntity.kt` (freie Box), `boss/VoidBossEntity.kt` (komplett: Fokus-Kreisbahn, neue Angriffe 16 Eruption,
17 Festgenagelt, 18 Tiefflug, Breath/Ram/Breach/Snap neu, Phase-III-Erschöpfung, Harpunen), `boss/LeviathanBelly.kt`
(5 Abschnitte), `boss/LeviathanCoreEntity.kt` (Rollen, Herzschild), `boss/HarpoonLauncher.kt`, `boss/ArenaRegistry.kt`,
`boss/VoidTribunal.kt` (v4 r 186, chunkweise, Kachel-Struktur), Mixins `SpawnHelperMixin`, `client.ClientPlayerEntityMixin`,
`client/boss/LeviathanClient.kt` (Reiten auf der Fläche), `client/fx/LeviathanAttackFx.kt` (Strahl, Harpune, Sektor),
Renderer nimmt `LeviathanBody.Motion`. Struktur-JSONs mit `spawn_overrides`, neues Set `void_tribunal_tiles`.
Nächster Schritt: `HHA_VOID_TEST=arena`, dann `fight`, Funde beheben.

### 14.11.2026 abends – Testergebnisse (voller `fight`-Lauf, 0 Exceptions, „fertig")
Alle Angriffe treffen einen still stehenden Spieler (0,1,2,3,4,5,6,7,8,9,10,16,18), Reiten gestrandet 12/12, im Flug
40/40, Tiefflug 24/24; Fassrolle/Rückenbeben/Schwanzpeitsche/Abtauchen werfen ab; Harpune nagelt fest (liegt, offen);
Bauch: Sog nach 15 s, Herz geschützt bis alle Klappen weg, Ausbruch ins Freie; Phase III: 40/160 Proben in Nahkampf-
reichweite mit echten Treffern; Endermen auf der Arena 0; Tod + Beute; Robustheit ok. Natürliche Arena (Haupt+Kacheln)
erzeugt Altar + alle 6 Harpunen.
- Hänger: keine langen Ticks, sondern lange Pausen *zwischen* Ticks beim Arenabau (asynchron geladene Chunks werden
  im Server-Thread fertiggestellt; 32 Chunks × Radius 1 gleichzeitig → bis 2,7 s). Fix: höchstens 4 Chunks gleichzeitig,
  Ticket-Radius 0, Ticket nach dem Schreiben sofort freigeben; Einmal-Berechnungen (Arena-Teile, Bauch-Entwurf)
  per `BossPrewarm` im Hintergrund beim Serverstart; Tick-/Pausen-Logger nur im Gametest.
- **Hänger-Ursache gefunden (Stack-Sampler im Gametest):** `writeTile` → `getBlockState` lud den Chunk synchron, obwohl
  `isChunkLoaded` true meldete (Chunk noch nicht FULL). Auch `getChunk(…, false)` blockiert im Server-Thread. Fix: Lade-Future
  von `addChunkLoadingTicket` abwarten (`isDone`), höchstens 4 gleichzeitig, Ticket danach frei. Ergebnis (`arena`,
  `HHA_VOID_ARENA=place`): Bau 665 Ticks/34 s, längster Tick 100 ms, keine Pausen mehr während des Baus. Übrige Meldungen
  sind Vanilla (Teleport in ungeladene Chunks, Speichern beim Beenden).
- Pausen *während* des Kampfes im Gametest = `ThreadingImpl.enterPhase` (Server wartet im Gleichschritt auf den
  Test-/Client-Thread) – Test-Artefakt, kein Spielproblem.
- Reiten: steile Stellen (Flanke, Sturzflug) werfen nicht mehr sofort ab, man rutscht zur Rückenmitte; Abwurf erst bei
  fast senkrechter Fläche > 8 Ticks. Ergebnis: gestrandet 12/12, Flug 33–40/40, Tiefflug 24/24.
- Letzte Läufe: `fight` voll (fight5/fight6) bis „fertig", Tod + Beute, 0 Exceptions; `fight` ride+phase2 (fight7);
  `arena` (Bau + natürliche Arena). `./gradlew build` grün (15.11.2026).

## Wenn du hier weitermachst (Runde 3)
- Tests: `HHA_VOID_TEST=fight` (Teilmengen `HHA_VOID_FIGHT=hitbox,attacks,ride,harpoon,phase2,belly,p3,death,robust`),
  `HHA_VOID_TEST=arena` (`HHA_VOID_ARENA=place` nur Bau, `=natural` nur natürliche Arena). Logzeilen: `[fight] SUMMARY`,
  `[ride] release (…)`, `[tick]` (lange Ticks/Pausen + Stack-Proben, nur im Gametest).
- Bekannt offen: Mahlstrom kann durch Deckung (Ruinen/Monolithen) verfehlen – gewollt; Sicht/Tracking auf den
  Außenplattformen hängt an der Server-Sichtweite (≥ 16 Chunks empfohlen).
