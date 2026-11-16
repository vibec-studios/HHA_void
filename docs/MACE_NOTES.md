# Void Mace – Neugestaltung (Arbeitsnotizen)

Nur Modell/Textur, keine Item-Logik. Nichts committet. **Stand: fertig**, Gametest und `./gradlew build` grün.

## Was gebaut wurde
- `tools/textures/GenVoidModels.java`: Mace-Teil neu (Abschnitte „Kriegshammer-Mace“, „Mace-Icon“, `maceDisplay`), Katana/Altar/Barren unverändert.
  - Kopf wie ein Kriegshammer: -x = Hals + Klotz + flache Schlagfläche, +x = gestufter Dorn mit glühender Spitze.
  - Augen-Platte (um 45° gedrehte Raute, Auge als eigenes leuchtendes Element) auf beiden z-Seiten.
  - Flammenkamm oben: Kristallzacken + zwei gekreuzte, animierte Flammen-Ebenen.
  - Knauf: vierzackiger Stern (Rautenplatte mit Edelstein, drei Arme, der vierte Zacken ist der Schaft).
  - Farben: `VMET` (fast schwarzes Netherit-Violett), Leuchtteile aus `GLOW`/`AME`/`FLAME`.
  - Icon (32x32, 8 Frames) wird in `maceIcon` Pixel für Pixel gesetzt, nicht gerendert (der Render war in 32x32 ein Klecks).
- Erzeugte Dateien: `models/item/void_mace_3d.json`, `textures/item/void_mace_3d.png(.mcmeta)`, `textures/item/void_mace.png(.mcmeta)`.
- `items/void_mace.json` blieb unverändert (3D-Modell in der Hand, 2D-Icon in GUI/Boden/Rahmen).

## Display-Werte (im Client-Gametest geprüft)
- 3rd Person: aus Vanilla `handheld_mace` abgeleitet (Rotation [0,-90,10]), Größe `MACE_3RD` = 0.85, Griffpunkt `MACE_GRIP` y = -2.5
  (Mitte der Griffwicklung). Haltung: Schlagfläche zeigt nach unten/vorn, Dorn nach oben, Kamm nach vorn.
- Ego-Sicht: Vanilla-Rotation [0,-90,-20] / 0.52 war unbrauchbar (Dorn ragt in die Kamera, Kopf ~50 % der Bildbreite,
  abgeschnitten). Jetzt eigene Werte `MACE_FP_ROT` = [-15,-40,12], `MACE_FP_T` = [2,1.5,-1.5], `MACE_1ST` = 0.38:
  Profil mit Augen-Platte sichtbar, Kopf gemessen ~210 px von 854 = 25 % der Bildbreite.

## Runde 3 (Mats' Spieltest) – FERTIG (ersetzt die Display-Werte im Abschnitt oben)
Feedback: Katana in Ego-Sicht komisch (F5 gut), Mace in Ego-Sicht komisch (Kopf zu groß/mittig/verdreht), Mace in
3rd Person viel größer (1,7–2×), Rüstungs-Netherit im End-Portal-Look (siehe ARMOR_NOTES).
- [x] `GenVoidModels.java`: Display-Werte als Arrays `SWORD_TP/SWORD_FP/MACE_TP/MACE_FP` = {rx,ry,rz,tx,ty,tz,Größe},
  `held(...)` berechnet die 3rd-Person-Verschiebung aus dem Griffpunkt, `display(tp, fp)` schreibt den Block.
- [x] Neue Hand-Vorschau: `java [-Dvoid_mace.fp="rx,ry,rz,tx,ty,tz,s;..."] tools/textures/GenVoidModels.java . --hand <dir>`
  rendert Ego-Sicht (rechts/links, 854x480, FOV 70) und Spielerpuppe (Seite/vorn/F5) offline in ~5 s. Mit den alten
  Werten deckungsgleich mit dem Spiel-Screenshot (geprüft) -> Werte lassen sich ohne Spielstart einstellen.
- [x] Neue Werte gesetzt und `models/item/void_sword_3d.json` + `void_mace_3d.json` neu erzeugt (nur der display-Block
  ändert sich, Texturen/Elemente byte-gleich):
  - Katana Ego: `SWORD_FP = pose(lean 6, pitch 35, roll 40, Hand [3,-1.2,-1.2], 0.64)` -> Rotation [-28.56,-53.17,8.21].
    3rd Person unverändert.
  - Mace Ego: `MACE_FP = pose(lean -4, pitch 22, roll 30, Hand [3.8,-1,-2.5], 0.36)` -> Rotation [-28.07,-58.3,-7.07].
  - Mace 3rd: Größe 0.85 -> 1.55 (1,82×), z-Rotation 10 -> -40 (50° steiler nach oben), Griffpunkt y -4 (`MACE_GRIP_3RD`).
    Achtung: z > 10 kippt den Hammer nach UNTEN, negativ = nach oben.
  - Linke Hand: `leftOf()` trägt jetzt das echte Spiegelbild ein. Das Spiel negiert y/z-Rotation des LINKEN Eintrags
    selbst (Transformation.apply, per javap geprüft) – der alte Mace-Eintrag [-15,40,-12] war deshalb NICHT gespiegelt.
- [x] `ArmorShowcase.kt`: zusätzliche Bilder `armor_26/27_1st_*_left` (Linkshänder), `armor_16..19_f5_*` (F5 mit FOV 70).
- [x] Gametest angesehen: Ego-Sicht Katana/Mace (Tag, Nacht, linke Hand) deckungsgleich mit der Vorschau; Mace in
  3rd Person/F5 riesig, Kopf vorn über Kopfhöhe, Knauf über dem Boden; Boden/Rahmen/Inventar (2D-Icons) unverändert.
- [x] `./gradlew build` grün. **Runde 3 fertig.**
- Nicht angefasst: Katana 3rd Person (Mats: „in F5 tuff“), Modelle, Atlanten, Icons, `items/*.json`.
- Stellschrauben: `SWORD_FP`/`MACE_FP` (`pose(lean, pitch, roll, Hand, Größe, Griff)`), `MACE_3RD`, z-Winkel in `MACE_TP`.

## Runde 4 (Mats' Feedback zur Ego-Sicht) – FERTIG (Gametest + Build grün)
Nur `firstperson_righthand/lefthand` geändert; 3rd Person/F5, GUI, Boden, Rahmen, Texturen unverändert.
- **Mace** („nicht von der Seite, sondern von hinten, ein bisschen angewinkelt zur Seite“):
  `MACE_FP = pose(lean -3, pitch 28, roll -8, Hand [3.5,-1.8,-2], 0.46)` -> Rotation [-6.23,-97.1,21.95].
  Merke: die Waffe sitzt rechts im Bild, der Blick zum Kopf geht ~38° nach rechts. „Genau von hinten“ ist deshalb
  roll -38, roll 0 ist schon schräg, ab roll +15 sieht man das Profil (Runde 3 hatte +30). Mit -8 sieht man die
  Rückseite (Dorn zum Spieler) und schräg die linke Seite mit Auge + Flammen-Glyphen.
- **Katana** („wieder größer; angewinkelt, so wie in Third Person“):
  `SWORD_FP = pose(lean 6, pitch 58, roll 38, Hand [4.2,-3.6,0.5], 1.3)` -> Rotation [-53.25,-56.96,5.83].
  Gleiche Haltung wie am Spielermodell: Klinge zeigt vom Spieler weg nach vorn, steigt ~30° an, Schneide unten,
  Flammenrücken oben. Im Bild läuft sie groß von der Tsuba (rechts unten) schräg Richtung Bildmitte.
  Die Größe 1.3 wirkt nicht riesig, weil die Klinge in die Tiefe läuft.
- Geprüft: `armor_20/21_1st_*` (Tag), `armor_28/29` (Nacht), `armor_26/27` (linke Hand, gespiegelt),
  F5 (`armor_10/17/19`) unverändert.

## Achtung beim Generieren
`java tools/textures/GenVoidModels.java .` schreibt auch Schwert/Altar/Barren neu (inhaltlich gleich, aber LF statt CRLF).
Um nur den Mace anzufassen: in einen leeren Ordner generieren und nur die fünf Mace-Dateien kopieren.

## Mögliche Feinarbeit (nicht nötig)
- Flammen-Ebenen blenden zwischen den Frames über (`interpolate`), dunkle Zungenspitzen wirken vor hellem Himmel leicht grau.
- Linke Hand in der Ego-Sicht ist nur gespiegelt eingetragen, im Gametest nicht fotografiert.
