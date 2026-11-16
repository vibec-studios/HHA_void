# Oruun, der Leviathan – Spezifikation für Modell, Texturen und Animation

Stand 13.11.2026. Nachfolger von `SPEC_WHALE.md` (der Sternenwal bleibt die Grundlage; Lore/IDs unverändert).
Die Boss-Logik, Arena, Hitboxen, Angriffe, Übergänge und die Bauch-Phase sind fertig (Server + Kamera + FX).
**Offen für Fable:** Modell (Leviathan-Look), Texturen, alle Haltungen tick-genau, Block-Texturen des Bauchs,
Kern-Modell. Bis dahin nutzt das Spiel das alte Wal-Modell skaliert mit vorläufigen Rigs (`client/boss/VoidBossModel.kt`).

Schnittstelle: `boss/WhalePose.kt` (Nummern + Dauer, Server setzt sie), `boss/LeviathanBody.kt` (Maße und Hitboxen –
das Modell muss dazu passen), `client/boss/VoidBossClient.kt` (`WhaleRenderer`, Skalierung `LeviathanBody.RENDER_SCALE`).

---

## 1. Größe und Proportionen

| | Wert |
|---|---|
| Renderer-Skalierung | **4,8** (vorher 2,0) – das alte Modell ist damit ≈ 58 Blöcke lang |
| Länge Maulspitze → Fluken-Ende | **≈ 54–58 Blöcke** (`MOUTH_FORWARD` 26,4 + `FLUKE_BACK` 27,6) |
| größter Körperdurchmesser | ≈ 16 Blöcke (Brust, 15 breit × 16 hoch) |
| Spannweite Brustflossen | ≈ 30 Blöcke |
| Fluke | ≈ 13 breit, 2,4 dick |
| Körpermitte | **7,2 Blöcke über der Entity-Position** (`BODY_UP`), Modell-Ursprung |

Bezugssystem wie bisher: Blick = Yaw/Pitch der Entity (Pitch positiv = Nase runter). Unten stehen Positionen relativ zur
Körpermitte: **f** = vorwärts, **s** = rechts (aus Sicht des Wals), **u** = oben (senkrecht zur Längsachse).

### Wichtige Punkte (Server benutzt sie für Angriffe/FX)

| Punkt | f | s | u | wofür |
|---|---|---|---|---|
| Maul (Mitte der Öffnung) | +26,4 | 0 | −1,9 | Sternenatem, Schnappen, Mahlstrom-Sog, Verschlingen |
| Augen | ≈ +19 | ±6,5 | +2,5 | (Look) – sollen in Nahaufnahmen der Cutscenes leuchten |
| Blasloch | +10,5 | 0 | +7,8 | Geysir, Rückenbeben, Schwachstelle ×1,5 (Box 3,6 × 1,6) |
| Sternenherz | +3,0 | 0 | −6,8 | Supernova-Sichtlinie, Schwachstelle (gestrandet ×2) |
| Brustflossen-Ansatz | +4 | ±8 | −4 | Flossen-Hitboxen bei s = ±11 |
| Fluke (Mitte) | −26,5 | 0 | +0,8 | Flukenschlag, Gravitationswelle |
| Flanke (Ausbruchwunde) | 0 | +9 | 0 | Ausbruch: hier reißt die Seite auf |

### Hitboxen (seit 14.11.2026): Vorwärtskinematik der Modellknochen

Die Boxen sind **keine** feste Tabelle mehr. `LeviathanBody.solve` rechnet für jede Lage die Weltmatrix aller Knochen
(gleiche Drehpunkte wie `tools/textures/GenWhale.java`, gleiche Reihenfolge Rz·Ry·Rx·Skalierung wie `ModelPart`) aus dem
Rig der Haltung (`boss/LeviathanRig.kt` – **wörtliche Kopie von `VoidBossModel.rig()`**, inkl. Überblendung) und der
Bewegung (`LeviathanBody.Motion`: Neigung, Schräglage, Kopfvorhalt, Biegung der 6 Wirbel, Schlagwelle – deterministisch
aus den letzten 8 Ticks; der Renderer zeichnet mit genau diesen Werten). Daraus 38 Volumen (`LeviathanBody.VOLUMES`):

| Gruppe | Volumen | Knochen | Art/Faktor |
|---|---|---|---|
| Kopf | head0–4 (Kuppel bis Lippe) | head | ×1 |
| Kiefer | jaw0–4 (inkl. Kehlsack) | jaw | ×1, nicht begehbar |
| Rumpf | neck0–1, chest0–2, belly0–1, hip0–1 | neck/chest/belly/hip | ×1 |
| Schwanz | tail0–5 | tail1–3 | ×0,9 |
| Fluke | fluke, fluke_l/_l2, fluke_r/_r2 | fluke + Lappen | ×0,75 (Platten) |
| Flossen | fin_l/_l2/_l3, fin_r/… | fin-Glieder | ×0,75 (Platten) |
| Schwachstellen | heart (×0,8, offen ×2), blowhole (×1,5) | heart / neck | |

Welt-AABB je Volumen: längs Quader, quer Ellipse (rundlicher Rumpf), Platten als Quader. **Begehbar** ist die glatte
Oberseite (`Frame.surface`: Senkrechte durch den Fuß ∩ Querschnitt), nicht die Boxkante.

**Wenn Fable eine Haltung ändert:** `LeviathanRig.rig()` mitziehen (sonst weichen Boxen und Bild auseinander). Neue
Knochen/Geometrie: `BONES`/`VOLUMES` in `LeviathanBody` anpassen.

**Gestrandet:** Entity-Y = Boden + `BEACH_LIFT` (Körpermitte 5 über dem Boden), Pitch 0; Lage/Rolle kommen aus `lie()`.

---

## 2. Look-Wünsche (Moby Dick / Leviathan)

- Uralt und vernarbt: tiefe Narben, alte **Harpunen** (Stangen + zerrissene Seile, 3–5 Stück, eine im Rücken nahe der
  Hüfte, eine in der Flanke), Seepocken-Krusten an Kopf und Flossenkanten (als „Sternenkruste“: Amethyst/Kristall).
- **Riesiger Kiefer**: Unterkiefer deutlich länger als der Oberkiefer (Pottwal-artig), Reihen kristalliner Zähne;
  das Maul muss sich für DEVOUR weit über 90° öffnen können (Unterkiefer klappt fast senkrecht nach unten).
- Kehlfalten (Furchenwal) als leuchtende Linien, die beim Einsaugen aufgehen.
- Blasloch als Hautlappen-Paar, das sich öffnet (BLOWHOLE, BACK_QUAKE).
- Haut: dunkelblau-violetter Sternenhimmel mit Sternbildern (wie bisher), aber rauer; Phase III: Risse mit Galaxie
  (bestehende `void_boss_p3*.png`). Flanke rechts: Wunde, die nach dem Ausbruch leuchtet (REELING, Phase III).
- Augen klein im Verhältnis, aber hell (sichtbar auf 100 Blöcke).
- Rückenkamm/Höcker (begehbar): flach genug, dass man darauf laufen kann; ein paar Kristall-„Griffe“.

---

## 3. Haltungen (`WhalePose`) – Timing Tick für Tick

Alle Zeiten in Ticks ab Beginn der Haltung (20 = 1 s). Überblendung macht der Renderer (`blendTicks`).
„Server“ = was gleichzeitig im Spiel passiert (Treffer, FX), damit die Animation dazu passt.

### Bestehend (behalten, an die neue Größe anpassen)
| # | Pose | Dauer | Ablauf |
|---|---|---|---|
| 0 | SWIM | ∞ | Gleiten; Flukenschlag ~3 s, langsamer als bisher (Masse!). |
| 1 | EMERGE | 120 | Intro (siehe SPEC_WHALE); 0–40 schält sich aus dem Riss, 40–90 bricht hervor, 90–120 Maul auf. |
| 2 | SONG | 60 | Gesang (wird kaum noch benutzt; ROAR ersetzt ihn meist). |
| 3 | TAIL_SLAM | 46 | 0–24 Hinterleib + Fluke steigen **13 Blöcke über den Rücken** (Server-Hitbox), 24–28 Peitschen nach unten (Fluke 9 Blöcke unter der Achse), **Einschlag Tick 28**, 30–46 Nachschwingen. |
| 4 | BREATH | 90 (Phase III: 150) | 0–25 Aufladen (Kehle glüht), 25–80 Maul weit offen (Strahl), Phase III bis 140 offen (zwei Durchgänge), dann schließen. |
| 5 | DIVE | ∞ | Sturz/Abtauchen, Flossen angelegt. Auch Phase-II-Szene und Abtauchen (Pitch bis 70°). |
| 6 | BEACHED | 140 | 0–9 Aufprall (federt), liegt flach (Profil oben), Kopf/Fluke zucken, Herz pulsiert, **120–140 stemmt er sich hoch**. |
| 7 | INHALE | 120 | Mahlstrom: 0–14 Maul auf, 14–108 maximal offen + aufgebläht, 108–120 schließen. |
| 8 | SWALLOW | 50 | Kieferschnappen: 0–18 Maul auf + Ausfall, **Tick 20 schnappt zu** (Treffer), 20–50 Würgen. |
| 9 | DEBRIS | 70 | Sternenregen beschwören: rollt ~90°, Flossen gespreizt. |
| 10 | SUPERNOVA | 140 | C-Form, Zittern, **Tick 110 Explosion** (aufreißen, gestreckt). |
| 11 | DEATH | 260 | 0–50 letztes Brüllen, **50–90 bäumt sich senkrecht auf**, 90–200 rollt auf den Rücken, Haut reißt auf, 200–260 treibt. Tick 150: Sternbild. |
| 12 | STAGGER | 20 | Treffer-Ruck (auch wenn ein Kern im Bauch zerstört wird). |
| 13 | HIDDEN | – | nicht zeichnen. |

### Neu
| # | Pose | Dauer | Tick für Tick |
|---|---|---|---|
| 14 | ROAR | 60 | 0–12 Kopf hoch, Maul reißt auf; **12–48 maximal offen, Körper bebt, Schallwelle Tick 14** (FX vom Server); Flossen weit, Fluke steil; 48–60 schließen. Intro (Tick 168), Phase-II-Szene (0 und 130), Sternlinge, Phase-III-Szene. |
| 15 | BARREL_ROLL | 60 | 0–20 zittert, rollt 15° an, Flossen angelegt (Warnung); **20–50 eine volle Rolle 360°** gleichmäßig (Server wirft Reiter bei Tick 28 ab); 50–60 auspendeln. Segmente sind 20–50 nicht begehbar. |
| 16 | RAM | ∞ | Rammstoß: Kopf leicht gesenkt, Maul einen Spalt, Flossen eng am Körper, maximale Schlagfrequenz. Flug dicht über dem Boden (Unterkante ≈ +1). |
| 17 | BREACH | 70 | Durchbruch: Server stellt Pitch ≈ −80° (senkrecht). 0–30 steigt, Fluke peitscht; **Durchstoß durch den Boden ≈ Tick 8–12** (FX); 30–50 Scheitel: bäumt sich, Maul auf, Flossen weit; 50–70 kippt nach vorn. |
| 18 | BLOWHOLE | 80 | 0–20 Rücken wölbt sich, Blasloch öffnet; **Fontänen-Stöße bei 22, 42, 62** (Server: Säule + Geysire am Boden); 70–80 schließen. |
| 19 | DEVOUR | 120 | 0–30 Kiefer reißt weiter auf als je zuvor (> 90°), Kehle bläht sich; 30–100 offen, Sog (Spieler fliegen hinein), Kopf zittert; **100–104 schnappt zu**; 110–120 schlucken (Beule wandert den Hals hinab). |
| 20 | REELING | 260 | Nach dem Ausbruch: liegt auf der Seite (Profil wie BEACHED), Flanke aufgerissen + leuchtend, Atem stoßweise (alle ~6 s Maul auf), Fluke zuckt; **240–260 rappelt sich auf**. Schadensfenster. |
| 21 | BACK_QUAKE | 50 | 0–24 Rücken zittert immer stärker (Hautwellen Kopf → Fluke); **24–30 Rücken schnellt hoch (Buckel)** – Reiter fliegen; 30–50 nachbeben. |
| 22 | TAIL_SWEEP | 56 | 0–26 Schwanz krümmt sich steil nach vorn (Skorpion), Fluke 14 Blöcke über dem Rücken; **26–46 Fluke fegt dicht über den Rücken von hinten (f −26) nach vorn (f +11)** (Server trifft Reiter, wo die Fluke vorbeikommt); 46–56 zurück. |
| 23 | GORGED | ∞ | Spieler im Bauch: träge, Bauch aufgebläht und von innen glühend (Kerne als Lichtpunkte durch die Haut), gelegentliches Krampfen. |
| 24 | BREAKOUT | 60 | 0–20 Körper krampft, rechte Flanke wölbt sich nach außen; **Tick 22 reißt die Flanke auf** (Licht, Spieler fliegen heraus); 22–60 sackt zusammen, kippt auf die Seite → REELING. |
| 25 | FLOOD | 70 | Flutwelle: 0–30 stellt sich quer, Fluke hebt sich seitlich; **30–36 Fluke schlägt flach durch die Luft** (Welle löst sich bei 36); 36–70 Nachschwingen, Kopf schaut der Welle nach. |

---

## 4. Kampfablauf, Angriffe und Kamera-Momente

Phasen: Intro → **I** (100–70 %) → Übergang „Die Tiefe erwacht“ → **II** (70–45 %) → **Verschlingen** → Bauch →
**Ausbruch** (Taumeln ×1,3, Herz ×2) → **III Raserei** → Tod. Leben: 900 + 420 je weiterem Spieler (10 Spieler: 4680),
Treffer höchstens 45, keine Unverwundbarkeits-Ticks (10 Spieler gleichzeitig).

Angriffe (`/voidboss attack <n>`): 0 Flukenschlag · 1 Sternenatem (Strahl über angezeigte Bahn, Brandspur, Splitter) ·
2 Sternenregen · 3 Rammstoß (jeder 2. mit Bruchlandung) · 4 Mahlstrom · 5 Durchbruch (Wal-Breach: durch den Boden, Bogen,
Einschlag 70 Blöcke weiter) · 6 Kieferschnappen · 7 Sternlinge · 8 Supernova (danach ausgebrannt am Boden) ·
9 Blasloch-Geysir · 10 Flutwelle · 11 Fassrolle · 12 Rückenbeben · 13 Schwanzpeitsche · 14 Abtauchen (durch den Boden) ·
15 Verschlingen · 16 Leere-Eruption (Sektoren) · 17 Festgenagelt (Harpunen der Außenplattformen) · 18 Tiefflug.
Bauch: Schlund → Magen → Gedärme → Lunge → Herzkammer (`boss/LeviathanBelly.kt`).

### Cutscenes (Kamera `client/fx/VoidCutscene.kt`, Choreografie `VoidBossEntity.scene*`)
| Szene | Länge | Kamera-Momente (für Animation wichtig) |
|---|---|---|
| Intro | 220 | 0–34 Riss am Himmel (Boden-Perspektive), 34–94 Nahfahrt am Kopf (EMERGE), 94–168 Kreisfahrt, **168–205 von unten: ROAR** |
| Phase II | 200 | 0–20 ROAR Nahaufnahme, 20–60 Abtauchen von der Kante gesehen (DIVE), 60–100 Boden reißt (Kamera am Boden), **100–150 von unten: BREACH durch den Rand**, 130 ROAR am Scheitel, Titel „II — DIE TIEFE ERWACHT“ |
| Verschlingen | 180 | 0–40 Anflug, **40–100 vom Boden: Maul öffnet sich (DEVOUR)**, 100–145 Kamera wird ins Maul gezogen, 126–140 Schwarzblende, 142 im Bauch |
| Bauch | 100 | aus Schwarz, Fahrt durch den Magen zu den Kernen, Titel „IM BAUCH DES LEVIATHANS“ |
| Ausbruch | 150 | 0–22 krampft (BREAKOUT), **22 Flanke reißt auf** (Blitz), 22–60 sackt zusammen, 60–130 Kreisfahrt um den Taumelnden (REELING), Titel „III — RASEREI“ |
| Tod | 260 | 0–60 Brüllen, 60–150 Kreisfahrt (aufbäumen, rollen), 150–230 Blick zum Sternbild |

---

## 5. Neue Blöcke und Entities (Optik-Wünsche)

| ID | Was | Wunsch |
|---|---|---|
| `hha_void:leviathan_flesh` | Magenwand/Boden | feuchtes, dunkel violett-rotes Fleisch, leicht pulsierend (animierte Textur willkommen) |
| `hha_void:leviathan_vein` | leuchtende Ader (Licht 13) | magenta/cyan glühende Adern, Sternenlicht fließt (animiert) |
| `hha_void:leviathan_rib` | Rippenbögen | Elfenbein-Knochen mit Sternenkruste |
| `hha_void:leviathan_acid` | Säure (7/16 Kollision, 12/16 sichtbar, Licht 7) | giftgrün-gelb, blubbernd (animiert), durchscheinend |
| `hha_void:leviathan_membrane` | Membran (Schlund + Flanke, bricht beim Ausbruch) | durchscheinend rosa mit dunklen Adern, Licht von außen schimmert durch |
| `hha_void:star_lift` | Sternenaufzug (Lichtsäule ohne Kollision) | senkrechte Lichtfäden, aufsteigende Funken (animiert) |
| `hha_void:leviathan_core` | Kern im Bauch (Entity 2,4 × 3,4) | derzeit Kristall-FX aus `CoreFx`; gewünscht: Organ/Sternenknoten an Sehnen, pulsiert im Herzschlag, bekommt Risse mit sinkendem Leben |
| `hha_void:leviathan_part` | Hitbox-Segment | unsichtbar (kein Modell nötig) |

Platzhalter-Texturen: `tools/textures/GenLeviathanBlocks.java`.
