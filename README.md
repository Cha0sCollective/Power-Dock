# Power Dock

Power Dock lets a Sable ship plug into a station's Power Grid circuit. The docks pull the ship into place, extend their arms, and connect the two circuits. Power can flow in either direction.

[Download Power Dock](https://github.com/Cha0sCollective/Power-Dock/releases/latest)

## What you need

Minecraft 1.21.1 with NeoForge 21.1.231 or newer, Create 6.0.10, Power Grid 0.6.2, Sable 2.0.5 or 2.0.6, and Architectury API. Install Power Dock on the server and on each client.

## Crafting

Make two Power Docks in a 3×4 Create Mechanical Crafter. This is the Docking Connector recipe with Power Grid Insulated Copper Wire in the two chute slots.

<img width="428" height="275" alt="image" src="https://github.com/user-attachments/assets/6a217408-53b6-418a-b114-86474de277fc" />

Use Crafter Slot Covers for the two blank slots in a full grid, everything folds into center then out the bottom

## Docking

1. Point one dock on the ship at the other on the station. Hold a Power Grid wire and aim at a copper post to see **Positive** or **Negative**. Wire both posts into the local circuit on each side.
2. Power both docks with redstone and bring the ship in slowly. The arms extend when the faces are less than one block apart. The amber lamps light and the comparators output 15 when the circuit closes.
3. Cut redstone to either dock to disconnect power, retract the arms, and release the ship.

## Building

Run `./gradlew build` with Java 21, or `gradlew.bat build` on Windows. Put the jar from `build/libs` in your Minecraft `mods` folder.

Run `./gradlew runGameTestServer` for the focused docking test with Sable 2.0.6. Add `-PsableVersion=2.0.5` to test the minimum supported version.
