# Power Dock

Power Dock connects Power Grid circuits between a Sable ship and a fixed station or between two Sable ships. The docks pull together, extend their arms, and transfer power in either direction.

[Download the stable release](https://github.com/Cha0sCollective/Power-Dock/releases/latest) · [Download the Loconautics beta](https://github.com/Cha0sCollective/Power-Dock/releases/tag/v0.4.0-beta3)

## What you need

Minecraft 1.21.1 with NeoForge 21.1.231 or newer, Create 6.0.10, Power Grid 0.6.2, Sable 2.0.5 or 2.0.6, and Architectury API. Install Power Dock on the server and on each client.

Hover over a Power Dock, Rail Jumper, Catenary Hanger, or Pantograph in your inventory and hold the Ponder key (W by default) for a walkthrough. The hanger's second chapter shows how to use Power Grid Copper Wire to build the overhead line.

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

### Loconautics trains

Train support was tested with Create: Loconautics 0.1.3 Beta and Create Simulated 1.3.0. Install both to use the train features. CC: Tweaked 1.120.2 is optional for wired data.

Mount matching docks facing each other on the carriage and on a station or Sable ship. Stop alongside the dock or under an overhead dock, with a clear gap of up to 1½ blocks between their faces. Arm both sides. The arms adjust to small offsets and the rails continue to guide the carriage. The circuit opens when the carriage moves out of reach or either dock is disarmed.

### Rail Jumpers

Mount either Rail Jumper on the same car as a Loconautics knuckle. Point its cord outlet toward the next car, the same way the knuckle faces. You can mount it in the middle, on a corner, or higher up. You can mix the two mounts on a train or across a single pair.

Each jumper uses the closest knuckle facing the same way on its own car. Put jumpers on matching corners to run two separate circuits across one knuckle pair. Extra sockets stay open when that end of the neighboring car has fewer jumpers.

- **Deck Rail Jumper.** Set it on an open deck. The two Power Grid posts and square data socket face up. With CC: Tweaked installed, place a Networking Cable directly above the block.
- **Flush Rail Jumper.** Replace one block in the end wall. Place it from inside while looking toward the gap between cars. The copper posts and data socket face into the car. Place a Networking Cable directly against the inside socket, then route it to a Wired Modem. The thin cable leaves both copper posts exposed. The cord outlet is on the outer face of the wall.

Wire the two copper posts into each car's Power Grid circuit. On a middle car, wire Positive to Positive and Negative to Negative between its two jumpers. Join the CC cables on that car as well to continue the wired network through it.

The cord appears and the amber lamps light when the knuckles couple. The cord bends as the cars move and disconnects power and data when the knuckles open. A comparator reads 15 while connected.

Craft two Deck Rail Jumpers with a Copper Cord between two Power Grid Sockets and a Brass Sheet below it. Combine a Deck Rail Jumper with an Iron Sheet to make a Flush Rail Jumper.

### Overhead power

Craft two Catenary Hangers with a Copper Sheet between two Iron Sheets and a Power Grid Socket below the Copper Sheet. Craft a Pantograph with a Brass Casing between two Iron Sheets, a Copper Sheet above the casing, and a Power Grid Socket below it.

Hold a Catenary Hanger, aim at a Create rail, and right-click. The hanger starts four blocks above the rail. You can aim at a straight section or partway through a curve or incline. Space hangers along bends and climbs so the wires have visible supports.

Hold Power Grid **Copper Wire** and sneak-right-click two hangers to preview both wires. If the track forks, sneak-right-click a rail on the branch you want. The on-screen prompt shows your selection, and the preview stays visible while you hold the wire. Sneak-right-click the second hanger again to confirm, or sneak-right-click into the air away from the track to cancel. Each span can follow up to 64 blocks of track. A hanger can feed multiple branches, and you can link back to an existing hanger to close a loop.

Look for the amber rail outline beneath a hanger. Sneak-right-click that rail with an empty hand to open its settings. Set the clear width from 3 to 13 blocks and the wire height from 4 to 12 blocks above the rail. The terminal box, crossbeam, and ceramic mounts move together. Use the arrows in the settings to highlight one span, then choose **Cut highlighted span** and confirm it.

Wire a Power Grid supply to any hanger's positive and negative posts with normal right-clicks. Several supplies can feed one line. They share the load according to the voltage and resistance of the circuit, and unequal supplies can feed current into one another. Keep each supply you want to use, its wiring, and its hanger loaded. The rest of the overhead line can carry power through unloaded hanger chunks. When upgrading from beta2, load both hangers on each existing span once so the line can be saved independently of the chunks.

The masts extend down to rail height. Add footings under them on raised track or uneven ground. The hanger housing is the collidable part of the fixture.

Fit a **Pantograph** on the roof of a Sable carriage. Place it while facing along the carriage so the folding arm runs along its length and the contact bar sits across the track. Connect its two posts to the onboard Power Grid circuit. Right-click with an empty hand to raise or lower it. The head has a working reach of about ¾ to 5½ blocks above its mount and can move 1¼ blocks side to side. The amber lamp and comparator indicate contact. When crossing between electrically separate lines, raise one pickup per onboard circuit to keep the sections isolated.

Loconautics can move an unattended scheduled train through unloaded chunks in ghost mode. With Loconautics station chunk loading enabled, the pantograph can pick up power again when the train reloads at a scheduled stop under a live overhead line.

For cab control, sneak-right-click the pantograph with an empty hand to open its receiver. Copy the two items from a transmitting **Create Redstone Link** into the matching slots in the same order, then select **Apply**. A powered link raises the pickup and turning the link off lowers it. A manual click still works and lasts until the signal changes. Select **Clear** and **Apply** to remove the binding.

## Pin Dock

Surround a Power Dock with eight pieces of Power Grid Insulated Copper Wire in a crafting table to make a Pin Dock.

Two Pin Docks join pins **1–8** and **Common** one for one. Hold a Power Grid wire and aim at a post to see its label. Connect the CPG Pinout controller's common terminal as well as the signals you want to carry. A separate two-contact Power Dock can charge the same craft while the Pin Docks carry its signals.

## CC:Tweaked

With CC:Tweaked installed, a computer can control the Power Dock and Pin Dock. Put a computer directly beside the dock or use a wired modem with peripheral access enabled. The peripheral type is `power_dock`.

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

Use `-PloconauticsTestMods=/path/to/mods` to run the carriage tests with Loconautics 0.1.3 Beta and Create Simulated 1.3.0 jars in that folder. These tests cover overhead power through curves, grades, and unloaded hanger chunks, plus power pickup at an unattended scheduled stop. Add `-PwithComputerCraft=true` to also check wired data through the Rail Jumpers.

Run `python tools/test_dock_restart.py --sable 2.0.6` to test a clean restart and an abrupt stop after a saved checkpoint. The runner starts fresh server processes and keeps the disposable worlds and logs in `build/dock-restart`.

The **Focused docking checks** workflow runs these checks on both supported Sable versions when started manually in GitHub Actions.
