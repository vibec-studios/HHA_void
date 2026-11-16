# Oruun – Flug-Animation (SWIM und Übergänge) – Fortschrittsnotiz

Aufgabe: das normale Herumfliegen natürlicher machen (Steigen/Sinken/Kurven, keine Ruckler). Keine Angriffe/Balancing.

## Befund (vorher)
- Pitch/Yaw kommen vom Server nur in 1,4°-Schritten (Byte) an und werden über 3 Ticks interpoliert. Bei langsamer
  Änderung (Kreisbahn, Höhe „atmet“) nickte der 26-Block-Wal darum in Stufen: 3 Ticks drehen, 5 Ticks stehen.
- Der Server dreht verzögert in die Flugrichtung (`faceVelocity`): der Wal stieg schon, bevor die Nase hochging, und
  flog die Kreisbahn mit ~7° nach außen gedrehter Nase.
- Renderer nahm `bodyYaw`; im Schweben (kaum Bewegung) dreht die Vanilla-`BodyControl` den Körper auf dem Client erst
  nach 10 Ticks nach.
- Haltung kam aus dem DataTracker (`castPose()`), ihr Alter aus dem Entity-Tick: bis zu 1 Tick lang lief die neue
  Haltung mit dem Alter der alten (Flossen sprangen kurz).
- Überblendung nur zwischen „vorige“ und „aktuelle“ Haltung: zwei Wechsel kurz nacheinander = Sprung.
- Pitch wird nur im Modell angewendet (`body.pitch`), `LivingEntityRenderer.setupTransforms` dreht nur um `bodyYaw` –
  keine doppelte Anwendung. Der Renderer überschreibt jetzt `state.bodyYaw` mit dem geglätteten Yaw.

## Was jetzt gilt
Alles clientseitig, je Bild mit der echten Bildzeit gerechnet (`WhaleRenderer.Memory` in `VoidBossClient.kt`);
der Server (Flugbahn, Angriffe, `faceVelocity`) ist unverändert.

- **Ausrichtung des Rumpfs**: Ziel = Richtung der sichtbaren Flugbahn (Positionsschritt je Tick, über zwei Ticks
  gemittelt, zwischen den Ticks interpoliert). Gewicht nach Tempo: unter ~0,1 Blöcke/Tick (Schweben) gelten die
  Entity-Winkel. Das Bild entfernt sich höchstens 18° (Yaw) / 14° (Pitch) von der Entity – Treffer und Maul bleiben,
  wo der Server sie rechnet. Treibt er quer/rückwärts (Kehrtwende mit Schwung), zählt für die Neigung nur die Entity.
  Danach kritisch gedämpfte Feder (5 Ticks; 3 in Angriffshaltungen) mit halber Vorsteuerung aus der
  Drehrate: er lenkt weich ein (kein Überschwingen) und liegt in der gleichmäßigen Kurve ~2,5 Ticks hinter der
  Tangente (auf der Kreisbahn ~3°, die der vorauseilende Kopf ausgleicht). Zielsprünge (Teleport, Aufschlag,
  Kehrtwende) sind auf 5,5°/Tick (Yaw) bzw. 4°/Tick (Pitch im Flug) begrenzt.
- **Hinterleib zieht nach**: Bauch, Schwanz 1-3, Fluke folgen je der Richtung des Vorgängers, verzögert um
  Segmentlänge / Tempo (Kette aus Tiefpässen) → der Körper liegt als Bogen auf der Bahn, beim Einleiten einer
  Steigung/Kurve hängt der Schwanz sichtbar nach und streckt sich im gleichmäßigen Steigflug wieder.
- **Kopf führt**: Hals + Kopf halten ~4 Ticks der Drehrate vor (Yaw und Pitch).
- **Schräglage**: aus der Yaw-Rate, Zeitkonstante 12 Ticks, ~7° auf der Kreisbahn, höchstens ~19°; der Kopf bleibt
  etwas aufrechter.
- **Steigen/Sinken** (`climb` −1..1 aus der Vertikalgeschwindigkeit, 10 Ticks geglättet): Steigen = Schlag ×1,45 und
  Takt ×0,8, Brustflossen rudern kräftiger mit angestellter Vorderkante. Sinken = Schlag ×0,4, Takt ×0,7, Flossen
  ausgebreitet. Im Sturzflug (DIVE) bleibt der schnelle kräftige Schlag (Rig-Kanal `GLIDE`).
- **Haltungswechsel**: Renderer nimmt `clientShownPose()` (passt immer zu `clientPoseAge`). Überblendet wird aus dem
  zuletzt gezeigten Rig (Schnappschuss im `Memory`), also auch mitten in einer Überblendung ohne Sprung. Zurück nach
  SWIM 14 statt 9 Ticks.
- Nach langem Nichtzeichnen (>10 Ticks) oder aus HIDDEN setzt alles ohne Einschwingen neu auf.

## Prüfen
- `HHA_VOID_TEST=whale HHA_VOID_WHALE=flight ./gradlew runClientGameTest --no-daemon`: feste Strecke (geradeaus,
  Steigflug, oben weiter, Sinken, Rechts-/Linkskurve, Kehre, Stopp; dieselben Formeln wie `fly()`/`faceVelocity()`,
  im Server-Tick gesetzt) und echte Kreisbahn eines KI-Wals ohne Gegner. Alle 5 Ticks ein Bild
  (`whale_30_flight_*`, `whale_31_cruise_rise_*`, `whale_32_cruise_top_*`, `whale_33_cruise_side_*`).
- Messung (zeitweise eingebaute Aufzeichnung je Bild, wieder entfernt): größter Sprung der Drehrate zwischen zwei
  Bildern 0,15°/Tick (Rumpf-Yaw) bei Raten bis 4,3°/Tick, Gelenke ≤ 0,1°/Tick – keine Stufen mehr. Im Kampf
  gleiche Größenordnung; die schnellste Drehung ist die Kehrtwende zu Beginn des Sturzflugs.

## Stand
- [x] Renderer, Modell, Entity-Zugriff `clientShownPose()`
- [x] Test-Sequenz `flight`, Bilder geprüft (Steigen, Sinken, beide Kurvenrichtungen, Kreisbahn)
- [x] `fight`-Durchlauf mit dem Umbau (ohne Fehler, Beute da)
- [x] Aufzeichnung entfernt; mit dem Endstand: `./gradlew build` grün, `whale` komplett (207 Bilder, alle Haltungen
      unverändert) und `fight` (66 Bilder, Beute da) ohne Fehler der Mod im Log

## Offen / Ideen
- Der Server dreht bei einer Kehrtwende (Beginn Sturzflug, Phase-2-Szene) mit 4°/Tick, während er schon in die neue
  Richtung fliegt – er treibt dann kurz rückwärts. Das Bild eilt dem höchstens 18° voraus; ganz weg bekäme man es nur
  über die Flugsteuerung selbst (bewusst nicht angefasst: Bewegungsbahnen/Angriffe).
- Die Zahlen zum Abstimmen stehen alle in `WhaleRenderer.updateRenderState` (Verzögerungen, Grenzen) und im Block
  „Steigen kostet Kraft“ in `WhaleModel.setAngles` (Schlagstärke, Flossen).
