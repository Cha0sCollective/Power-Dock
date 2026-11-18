# Power Dock

Power Dock connects Power Grid circuits between a Sable ship and a fixed station or between two Sable ships. The docks pull together, extend their arms, and transfer power in either direction.

[Download Power Dock](https://github.com/Cha0sCollective/Power-Dock/releases/latest)

## What you need

Minecraft 1.21.1 with NeoForge 21.1.231 or newer, Create 6.0.10, Power Grid 0.6.2, Sable 2.0.5 or 2.0.6, and Architectury API. Install Power Dock on the server and on each client.

## Crafting

Make two Power Docks in a 3×4 Create Mechanical Crafter. This is the Docking Connector recipe with Power Grid Insulated Copper Wire in the two chute slots.

<img width="428" height="275" alt="image" src="https://github.com/user-attachments/assets/6a217408-53b6-418a-b114-86474de277fc" />

Use Crafter Slot Covers for the two blank slots in a full grid, everything folds into center then out the bottom

## Docking

1. Mount the docks on separate Sable ships, or mount one on a ship and one on a fixed station. Point them at each other and wire both posts into the local circuit on each side. Hold a Power Grid wire and aim at a copper post to see **Positive** or **Negative**.
2. Power both docks with redstone and bring them together slowly. When docking two ships, match their speed before closing the gap. The arms extend when the faces are less than one block apart. The amber lamps light and the comparators output 15 when the circuit closes.
3. Cut redstone to either dock in a pair to disconnect its power and retract its arms. The craft separates when its last dock pair is released.

A carrier can hold several Sable craft at once. Give each plane or supply pod its own dock and control the matching carrier dock with a separate redstone switch. For a craft with multiple dock pairs, match the spacing and orientation on both hulls so all the heads meet when the hulls line up.

## Building

Run `./gradlew build` with Java 21, or `gradlew.bat build` on Windows. Put the jar from `build/libs` in your Minecraft `mods` folder.

Run `./gradlew runGameTestServer` for the focused docking test with Sable 2.0.6. Add `-PsableVersion=2.0.5` to test the minimum supported version.
