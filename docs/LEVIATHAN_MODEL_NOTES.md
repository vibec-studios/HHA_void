# Leviathan – Modell, Texturen, Animation – Fortschrittsnotiz

Auftrag: neues Modell + Texturen + alle Posen 0–25 für Oruun (Leviathan), Bauch-Block-Texturen, Sternenkern-Modell.
Keine Spiellogik ändern. Nicht committen. Spec: `docs/SPEC_LEVIATHAN.md`.

## Aufbau
- **Einheiten**: 1 Modelleinheit = 0,3 Blöcke (Skalierung 4,8). +Y unten, Blick −Z, links = +X (rechts des Wals = −X).
  Umrechnung Spec → Modell: z = −f/0,3, x = −s/0,3, y = −u/0,3.
- **Generator** `tools/textures/GenWhale.java` ist die einzige Quelle (Geometrie, UV, 4 Texturen, `WhaleModelData.kt`).
  Ruhedrehung je Teil (Harpunen), Textur 3 Texel/Einheit (2040×1632), Vorschau mit Hitbox-Drahtgitter:
  `java tools/textures/GenWhale.java . --preview <ordner>`.
- **Knochen**: root (Neigung, Stauchung gestrandet) → body (Rollen/Gieren) → chest → neck → head → jaw → throat (Kehlsack,
  Lamellen pj0-5); neck_skin (Blasloch-Lappen blow_l/r, Lamellen pn0-5); chest → belly → hip → tail1 → tail2 → tail3 → fluke
  → fluke_l/_l2, fluke_r/_r2 (6 Wirbelgelenke); fin_l/_l2/_l3, fin_r/…; heart + heart_flare; cheek_l/r; Harpunen harp1-5
  mit Seilen harpN_ropeM; wound (rechte Flanke: Glut, Lappen flap_*, wound_rays), bulge, gorge_c/gorge_b.
- **Rig** (`client/boss/VoidBossModel.kt`, `WhaleModel`): je Haltung ein FloatArray (62 Kanäle), Überblendung aus dem zuletzt
  gezeigten Rig, Dauerbewegung (Schlagwelle, Flossen, Herzschlag, Seile). Gestrandet: `lie()` (Rolle 0,95 rad nach links,
  Stauchung 0,34, Schwanz hängt durch) passend zu `BEACHED_PROFILE`.
- **Renderer** (`VoidBossClient.kt`): 6 nachziehende Segmente, Seil-Pendel (Schwerkraft + Fahrtwind im Rumpf).

## Stand
- [x] Generator: Geometrie (72 Teile, 440 Quader) – Pottwal-Kopf, langer Unterkiefer, Kristallzähne, Kehlsack/-falten,
      Blasloch-Lappen, 5 Harpunen mit Seilen, Kruste, Rückenhöcker, Wunde rechts, Bauchglühen
- [x] Generator: Texturen (Haut mit Narben/Altersflecken/Lederzellen, P3 mit Galaxie-Rissen, Glow)
- [x] `VoidBossModel.kt`: Rig + alle Posen 0–25 (vom Vorgänger geschrieben, StarlingModel wiederhergestellt)
- [x] `VoidBossClient.kt`: 6 Wirbel, Seile (ropeA/ropeB/bodyRot), größere Cull-Box
- [x] Posen im Spiel geprüft (Lauf 1): Flossen in Rückenbeben/Flukenschlag/Peitsche nach hinten statt „Beine“, Supernova-Riss exakt Tick 110, Brüll-Schockwelle Tick 14, Schlund-Glut als Verlauf statt Kasten, Altersflecken dezenter
- Hinweis Vitrine: FAR-Kamera (117 Blöcke) liegt außerhalb der Tracking-Weite des Testservers – danach war der Wal weg; `pose()` stellt die Kamera jetzt vorher zurück
- [x] Bauch-Blöcke: `GenLeviathanBlocks.java` neu (Fleisch 8 Bilder Herzschlag, Ader 16 Bilder Lichtfluss, Rippe mit Amethystkruste, Säure 16 Bilder Blasen/Kaustik, Membran 8 Bilder, Aufzug 16 Bilder), Vorschau `--preview`
- [x] Sternenkern: `core()` in GenWhale (Kristall, 4 Blätter, Sehnen, Splitter; 3 Texturstufen heil/Risse/zerborsten), `CoreModel`/`CoreRenderer` in LeviathanClient.kt, CoreFx zeichnet nur noch den Lichtkranz
- [x] `WhaleShowcase.kt`: alle Posen zu Schlüssel-Ticks (`keys()`), Hitbox-Bilder (swim, beached, reeling)
- [x] Tests: `whale` (day,poses,beached,p3,night: 123 Bilder, 0 Exceptions), `fight` (voll inkl. Bauch, Ausbruch, Tod, robust: 176 Bilder, „[fight] fertig“, 0 Exceptions), `./gradlew build` grün (14.11.2026)

## Offen / Ideen
- Flugstrecke (`HHA_VOID_WHALE=flight`, 144 Bilder) geprüft: Steigen/Sinken/Kurven flüssig, Seile wehen im Fahrtwind.
- Im Testclient ist der Wal ab ~115 Blöcken nicht mehr sichtbar (Tracking-/Sichtweite des Testservers), im echten Spiel Server-Einstellung.
