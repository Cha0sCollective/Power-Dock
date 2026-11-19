# Power Dock

Power Dock connects Power Grid circuits between a Sable ship and a fixed station or between two Sable ships. The docks pull together, extend their arms, and transfer power in either direction.

[Download Power Dock](https://github.com/Cha0sCollective/Power-Dock/releases/latest)

## What you need

Minecraft 1.21.1 with NeoForge 21.1.231 or newer, Create 6.0.10, Power Grid 0.6.2, Sable 2.0.5 or 2.0.6, and Architectury API. Install Power Dock on the server and on each client.

## Crafting

Make two Power Docks in a 3×4 Create Mechanical Crafter. This is the Docking Connector recipe with Power Grid Insulated Copper Wire in the two chute slots.

<img width="428" height="275" alt="image" src="https://github.com/user-attachments/assets/6a217408-53b6-418a-b114-86474de277fc" />

<img width="400" height="233" alt="image" src="https://github.com/user-attachments/assets/37c4de49-a86c-4d9b-b03a-2c235fb82a1e" />


Use Crafter Slot Covers for the two blank slots in a full grid, everything folds into center then out the bottom

## Docking

1. Mount the docks on separate Sable ships, or mount one on a ship and one on a fixed station. Point them at each other and wire both posts into the local circuit on each side. Hold a Power Grid wire and aim at a copper post to see **Positive** or **Negative**.
2. Power both docks with redstone and bring them together slowly. When docking two ships, match their speed before closing the gap. The arms extend when the faces are less than one block apart. The amber lamps light and the comparators output 15 when the circuit closes.
3. Cut redstone to either dock in a pair to disconnect its power and retract its arms. The craft separates when its last dock pair is released.

A carrier can hold several Sable craft at once. Give each plane or supply pod its own dock and control the matching carrier dock with a separate redstone switch. For a craft with multiple dock pairs, match the spacing and orientation on both hulls so all the heads meet when the hulls line up.

## Pin Dock

Surround a Power Dock with eight pieces of Power Grid Insulated Copper Wire in a crafting table to make a Pin Dock.

Two Pin Docks join pins **1–8** and **Common** one for one. Hold a Power Grid wire and aim at a post to see its label. Connect the CPG Pinout controller's common terminal as well as the signals you want to carry. A separate two-contact Power Dock can charge the same craft while the Pin Docks carry its signals.

## CC:Tweaked

With CC:Tweaked installed, both dock types can be controlled by a computer. Put a computer directly beside the dock or use a wired modem with peripheral access enabled. The peripheral type is `power_dock`.

To join the wired networks, connect a CC:Tweaked wired modem or network cable directly to the **back** of each Pin Dock. Connect that modem or cable to the network on its own hull. Turn on peripheral access at the modems next to the devices you want to share. When the Pin Docks close, the two networks can see each other's exposed peripherals. Opening the docks splits the networks once the last wired path between them is gone.

| Lua method | What it does |
| --- | --- |
| `getState()` | Returns `disarmed`, `armed`, `aligning`, or `docked`. |
| `setArmed(true)` | Arms this dock under computer control. The other dock must also be armed. |
| `release()` or `setArmed(false)` | Opens this pair and keeps this dock disarmed until you re-arm it or return to redstone control. |
| `useRedstone()` | Hands control back to the dock's redstone input. By default, both kinds of dock start in redstone mode. |
| `getContactCount()` | Returns `2` on a Power Dock or `9` on a Pin Dock. |
| `getVoltage(pin, reference)` | Returns signed voltage from the chosen reference to the pin, or `nil` when they share no solved circuit. The default reference is `9` (Common) on a Pin Dock and `2` (Negative) on a Power Dock. |

## Building

Run `./gradlew build` with Java 21, or `gradlew.bat build` on Windows. Put the jar from `build/libs` in your Minecraft `mods` folder.

Run `./gradlew runGameTestServer` for the focused docking tests with Sable 2.0.6. Add `-PsableVersion=2.0.5` to test the minimum supported version, or add `-PwithComputerCraft=true` to run the wired networking fixture with CC:Tweaked 1.120.2.

Run `python tools/test_dock_restart.py --sable 2.0.6` to test a clean restart and an abrupt stop after a saved checkpoint. The runner starts fresh server processes and keeps the disposable worlds and logs in `build/dock-restart`.

The **Focused docking checks** workflow runs these checks on both supported Sable versions when started manually in GitHub Actions.
