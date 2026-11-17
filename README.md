# Power Dock

Power Dock lets a Sable ship plug into a station's Power Grid circuit. The docks pull the ship into place, extend their arms, and connect the two circuits. Power can flow in either direction.

## What you need

Minecraft 1.21.1 with NeoForge 21.1.231 or newer, Create 6.0.10, Power Grid 0.6.2, Sable 2.0.6, and Architectury API. Install Power Dock on the server and on each client.

## Crafting

Make two Power Docks in a 3×4 Create Mechanical Crafter. This is the Docking Connector recipe with Power Grid Insulated Copper Wire in the two chute slots.

```text
Iron Sheet    Insulated Copper Wire    Iron Sheet
              Insulated Copper Wire
Piston        Brass Casing             Piston
Brass Sheet   Electron Tube            Brass Sheet
```

Use Crafter Slot Covers for the two blank slots in a full grid.

## Docking

1. Point one dock on the ship at the other on the station. Wire both terminals on each side. Red is positive and blue is return.
2. Power both docks with redstone and bring the ship in slowly. The arms extend when the faces are less than one block apart. The amber lamps light and the comparators output 15 when the circuit closes.
3. Cut redstone to either dock to disconnect power, retract the arms, and release the ship.

## Building

Run `./gradlew build` with Java 21, or `gradlew.bat build` on Windows. Put the jar from `build/libs` in your Minecraft `mods` folder.

Run `./gradlew runGameTestServer` for the focused docking test.
