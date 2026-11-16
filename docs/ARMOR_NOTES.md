# Void-Rüstung / Waffen / Barren / Altar – Fortschrittsnotiz

Auftrag: nur Modelle + Texturen im Stil der Blood-Rüstung (hha-blood). Keine Logik/Fähigkeiten ändern.

## Stand

| Teil | Stand |
|---|---|
| Rüstung (Ebenen + Glow + Icons) | fertig, im Spiel geprüft (Tag/Nacht) |
| Glow-Mixin `EquipmentRendererMixin` | fertig, im Spiel geprüft (leuchtet nachts, pulsiert) |
| Unsichtbarkeit | geprüft: volles Set + Trank am Spieler -> Rüstung samt Glow weg; ohne Schuhe wieder da |
| Void Sword = **Katana** (3D + Icon) | fertig, im Spiel geprüft (1st/3rd Person, Vergleich mit Vanilla-Schwert, Inventar, Boden, Rahmen) |
| Void Mace = **Zierstab mit Strahlenkopf** (3D + Icon) | fertig, im Spiel geprüft (wie oben, Vergleich mit Vanilla-Mace) |
| Void Ingot | fertig (Inventar, Boden, Rahmen geprüft) |
| Altar "Ruf der Tiefe" | fertig, im Spiel geprüft (Tag/Nacht) |
| Aufräumen | `void_weapon_model.png(.mcmeta)`, `void_*_in_hand.json` gelöscht; items/*.json zeigen auf `*_3d` |
| Gametest `ArmorShowcase.kt` | fertig, 47 Bilder |

Offen: nichts Zwingendes. Größe der Waffen in der Hand ist Geschmackssache (siehe unten, vier Konstanten).

## Entscheidungen

- **Trims** (Vorgabe von Mats): Material amethyst; Helm = `vex`, Brustplatte = `eye`, Hose = `tide`,
  Schuhe = `flow` (Konstanten `HELMET_TRIM`/`CHEST_TRIM`/`LEGS_TRIM`/`BOOTS_TRIM` in `GenVoidArmour.java`).
  Helm, Brust und Schuhe teilen die humanoid-Ebene -> Muster je UV-Bereich.
- **Palette**: Vanilla `amethyst`, Farbton unverändert, nur ~10 % heller und etwas satter – bleibt klar als
  Amethyst-Trim erkennbar. Die Glow-Ebene zieht die Helligkeit hoch und lässt die dunkelsten Stufen weg.
- **Glow**: zweite Equipment-Ebene `hha_void:void_glow`, gezeichnet mit der `eyes`-Pipeline (volle Helligkeit,
  normales Alpha-Blending, NICHT additiv). Puls 2,6 s, 70–100 %.
- **Falle**: Vanillas `RenderLayers.eyes()` allein zeigt NICHTS – Rüstung wird mit
  `LayeringTransform.VIEW_OFFSET_Z_LAYERING` gezeichnet, die eyes-Ebene läge dahinter und fällt durch den
  Tiefentest. Das Mixin baut deshalb einen eigenen RenderLayer (eyes-Pipeline + dasselbe Layering).
  Das Blood-Addon nutzt `RenderLayers.eyes()` direkt und hat vermutlich denselben Fehler (dort prüfen!).
- **Unsichtbarkeit**: `ArmorFeatureRendererMixin` bricht `ArmorFeatureRenderer.render` am Anfang ab, dadurch
  wird `EquipmentRenderer` gar nicht aufgerufen – die Glow-Ebene verschwindet mit. Nichts daran geändert.
- **Waffen** (Runde 2, Mats' Referenzbilder): Schwert -> Katana, Mace -> langer Zierstab mit Strahlenkopf.
  Die erste Fassung (Walknochen-Schwert, Singularitäts-Mace mit Akkretionsring) ist ersetzt.
  - Modelle stehen aufrecht; Display = Vanilla `handheld`/`handheld_mace` mit z-Rotation −45°
    (55 -> 10, 25 -> −20). Haltung im Spiel mit Vanilla verglichen (Bilder `armor_06/07_hand_*`).
  - **Katana**: Schneide zeigt im aufrechten Modell nach −x, Rücken nach +x, Spitze weicht zum Rücken aus.
    Nur so liegt es richtig in der Hand (3rd Person: Schneide unten, Spitze schwingt nach oben, Flammen
    lodern oben am Rücken; Ego-Sicht: Schneide vom Spieler weg). Andersherum hielte man es verkehrt.
    Das Icon wird deshalb von der Rückseite gerendert (`turn` 205), damit es wie ein übliches Katana-Icon
    aussieht (Schneide unten rechts).
  - **Flammen**: eine hauchdünne Ebene in der Klingenfläche mit Cutout-Pixeln (leuchtend, 8 Frames, nahtlos):
    zusammenhängende Zungen am Rücken, schmaler Saum an der Schneide, wenige Funken. Quer-Ebenen und
    verstreutes Rauschen wurden verworfen (verdeckten die Klinge bzw. sahen wie Schmutz aus). Genau von der
    Schneide/vom Rücken her gesehen sind die Flammen unsichtbar (Ebene steht auf der Kante).
    Im Icon bekommen Flammen keinen Umriss und bestimmen den Bildausschnitt nicht.
  - **Stab**: Griffwicklung im unteren Drittel, die Hand sitzt dort (`MACE_GRIP`); Kopf = dunkler Kern +
    17 Strahlen (je 3 sich verjüngende Stücke), Puls läuft nach außen.
  - Größe/Griff (Stand Runde 4): `SWORD_3RD = 0.95`, `SWORD_1ST = 1.3`, `MACE_3RD = 1.55`, `MACE_1ST = 0.46`,
    `SWORD_GRIP`/`MACE_GRIP`/`MACE_GRIP_3RD` in `GenVoidModels.java` (Vanilla 0.85 / 0.68 / 1.0 / 0.9). In 3rd
    Person wird die Verschiebung automatisch so berechnet, dass der Griffpunkt an der Vanilla-Handposition liegt
    (`held`); die Ego-Sicht hat eigene Haltungen (`pose`), Details in `MACE_NOTES.md` (Runde 3 + 4).
  - Atlanten 128x128, 8 Frames (Schwert frametime 3, Stab 4).
- Icons 32x32 aus dem Modell gerendert. GUI/Boden/Rahmen/Regal flach, sonst 3D.
- Die großen violetten "Flügel"/Splitter um den SPIELER in den 3rd-Person-Bildern sind die vorhandene
  Set-Passive (kreisende Splitter), nicht das Schwert. Mannequins zeigen die Waffe ohne Passive.

## Runde 3 (Mats' Spieltest): Netherit im End-Portal-Look – FERTIG (Gametest + Build grün)

Wunsch: „Netherit der Rüstung in der Farbe des End-Portals, Amethyst-Trims bleiben.“
- [x] `GenVoidArmour.java`: neue Funktion `portal()` färbt die Vanilla-Netherit-Textur nach Helligkeit auf die
  Schwarzblau-Rampe `PORTAL` um (Fugen fast schwarz, Plattenkanten stahlblau -> Plattenstruktur bleibt lesbar) und
  setzt Sternpunkte (`STARS`: 60 % türkis, 20 % cyanblau, 20 % violett; je schwach/mittel/hell) nur auf Flächen
  mittlerer Helligkeit, nie zwei nebeneinander. Dichte `STAR_DENSITY` 0.115 (Ebenen) / `STAR_DENSITY_ICON` 0.16.
- [x] Trims unverändert (gleiche Palette, gleiche Muster, liegen über den Sternen).
- [x] Glow-Ebene: zusätzlich die mittleren/hellen Sterne halbtransparent (Alpha 110/215) -> leises Funkeln nachts.
- [x] Item-Icons: 4 Frames (`frames:[0,1,2,3]`, frametime 26, interpoliert): Trim normal/glühend wie bisher,
  dazu wandert die Helligkeit der Sterne (Funkeln).
- [x] Texturen geschrieben (4 Ebenen-PNGs, 4 Icons + mcmeta). Papierpuppen-Vorschau geprüft.
- [x] Gametest angesehen (`armor_02/04/05_day|night_*`, `armor_30_inventory`, Rahmen, Boden): Metall tief
  schwarzblau, Platten lesbar, Sterne türkis mit wenigen violetten; nachts funkeln die Sterne schwach, die Trims
  leuchten wie vorher. Einzige Korrektur: kein Stern auf dem Nasensteg des Helms (saß wie ein drittes Auge im Gesicht).
- [x] `./gradlew build` grün.
- Stellschrauben, falls Mats es anders will: `PORTAL` (Rampe; obere Stufen dunkler = schwärzer, aber Platten
  schlechter lesbar), `STAR_DENSITY`, Alpha der Glow-Sterne in `starGlow()` (110/215).

Waffen-Feedback derselben Runde: siehe `MACE_NOTES.md` (Abschnitt „Runde 3“).

## Werkzeuge

- `tools/textures/GenVoidArmour.java` – Rüstungsebenen, Glow-Ebenen, Icons. `--preview out.png` = Papierpuppe.
- `tools/textures/GenVoidModels.java` – Katana, Stab-Mace (3D + Icons), Altar, Barren.
  `--preview <dir>` schreibt nur Vorschau-PNGs, `--hand <dir>` rendert Ego-Sicht + Spielerpuppe mit den
  Display-Werten (Varianten per `-Dvoid_sword.fp=...` usw., siehe Kommentar „Hand-Vorschau“ im Generator).
- Spieltest: `HHA_VOID_TEST=armor ./gradlew runClientGameTest --no-daemon`,
  Bilder in `build/run/clientGameTest/screenshots`.
